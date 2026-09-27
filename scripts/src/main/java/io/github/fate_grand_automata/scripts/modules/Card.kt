package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.SpamConfigPerTeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@ScriptScope
class Card @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
    private val state: BattleState,
    private val spamConfig: SpamConfigPerTeamSlot,
    private val caster: Caster,
    private val parser: CardParser,
    private val priority: FaceCardPriority,
    private val braveChains: ApplyBraveChains,
    private val battleConfig: IBattleConfig,
    private val recovery: RecoveryWatchdog
) : IFgoAutomataApi by api {

    fun readCommandCards(): CardParser.Result {
        var lastResult: CardParser.Result = CardParser.Result.Unsafe(
            emptyList(), CardParser.Reason.IncompleteSlots
        )
        repeat(CARD_RECOGNITION_ATTEMPTS) { attempt ->
            lastResult = useSameSnapIn { parser.classify(parser.parse()) }
            if (lastResult is CardParser.Result.Normal) return lastResult
            if (attempt < CARD_RECOGNITION_ATTEMPTS - 1 &&
                (lastResult is CardParser.Result.NeedsRecovery || lastResult is CardParser.Result.Unsafe)
            ) {
                val decision = recovery.request(
                    reason = "command-card-recognition",
                    context = RecoveryWatchdog.Context(state.stage, state.turn, "read-cards")
                )
                if (decision.level == RecoveryWatchdog.Level.Stop) return CardParser.Result.Unsafe(
                    lastResult.cards,
                    CardParser.Reason.IncompleteSlots
                )
            }
            CARD_RECOGNITION_RETRY_DELAY.wait()
        }
        return lastResult
    }

    private companion object {
        const val CARD_RECOGNITION_ATTEMPTS = 3
        const val SELECTION_CLICK_ATTEMPTS = 2
        const val SELECTION_UNCHANGED_SIMILARITY = 0.86
        const val SELECTION_SUBMIT_POLLS = 8
        val CARD_RECOGNITION_RETRY_DELAY = 300.milliseconds
        val SELECTION_SETTLE_DELAY = 250.milliseconds
        val SELECTION_EXTRA_SETTLE_DELAY = 350.milliseconds
        val SELECTION_RETRY_DELAY = 180.milliseconds
        val SELECTION_SUBMIT_POLL_DELAY = 250.milliseconds
    }

    private val spamNps: Set<CommandCard.NP>
        get() =
            (FieldSlot.list.zip(CommandCard.NP.list))
                .mapNotNull { (servantSlot, np) ->
                    val teamSlot = servantTracker.deployed[servantSlot] ?: return@mapNotNull null
                    val npSpamConfig = spamConfig[teamSlot].np

                    if (caster.canSpam(npSpamConfig.spam) && (state.stage + 1) in npSpamConfig.waves)
                        np
                    else null
                }
                .toSet()


    /**
     * Build the NP request for this turn before entering Attack.
     *
     * The user's fixed frontline uses slot A for Larva/Tiamat. On the second turn of each wave,
     * slot A's NP is probed even when the static AutoSkill command omitted it. It is only counted
     * if the Attack screen visibly accepts the tap.
     */
    fun prepareNpUsage(requested: NPUsage): NPUsage {
        val nps = buildSet {
            addAll(requested.nps)
            addAll(spamNps)
            if (state.turn == 1) add(CommandCard.NP.A)
        }

        return NPUsage(
            nps = nps,
            cardsBeforeNP = if (state.turn == 1 && CommandCard.NP.A in nps) {
                0
            } else {
                requested.cardsBeforeNP
            }
        )
    }

    private fun pickCards(
        cards: List<ParsedCard>,
        npUsage: NPUsage
    ): List<CommandCard.Face> {
        val cardsOrderedByPriority = priority.sort(cards, state.stage)

        fun <T> List<T>.inCurrentWave(default: T) =
            if (isNotEmpty())
                this[state.stage.coerceIn(indices)]
            else default

        val braveChainsPerWave = battleConfig.braveChains
        val rearrangeCardsPerWave = battleConfig.rearrangeCards

        return braveChains.pick(
            cards = cardsOrderedByPriority,
            npUsage = npUsage,
            braveChains = braveChainsPerWave.inCurrentWave(BraveChainEnum.None),
            rearrange = rearrangeCardsPerWave.inCurrentWave(false)
        ).map { it.card }
    }

    sealed class SelectionResult {
        data object Submitted : SelectionResult()
        data class NeedsRestart(val reason: String) : SelectionResult()
    }

    private enum class TapResult { Confirmed, Unavailable, Failed }

    /**
     * The Attack page is considered present while at least two command-card type strips remain
     * recognisable. This remains true after one or two cards have been selected, but becomes false
     * as soon as FGO accepts the third command and leaves the selection page.
     */
    fun isAttackSelectionScreenVisible(): Boolean = useSameSnapIn {
        CommandCard.Face.list.count { face ->
            val region = locations.attack.typeRegion(face)
            images[Images.Buster] in region ||
                images[Images.Arts] in region ||
                images[Images.Quick] in region
        } >= 2
    }

    private fun tapAndConfirm(command: CommandCard): TapResult {
        repeat(SELECTION_CLICK_ATTEMPTS) { attempt ->
            if (!isAttackSelectionScreenVisible()) return TapResult.Failed

            val probe = locations.attack.selectionProbeRegion(command)
            val before = probe.getPattern(
                "CommandSelection:${state.stage}:${state.turn}:$command:$attempt"
            )

            when (command) {
                is CommandCard.Face -> caster.use(command)

                is CommandCard.NP -> when (caster.use(command)) {
                    Caster.NpTapResult.Sent -> Unit
                    Caster.NpTapResult.Blocked -> {
                        before.close()
                        return TapResult.Unavailable
                    }
                    Caster.NpTapResult.NotSent -> {
                        before.close()
                        return TapResult.Failed
                    }
                }
            }

            SELECTION_SETTLE_DELAY.wait()
            var changed = probe.find(before, similarity = SELECTION_UNCHANGED_SIMILARITY) == null

            if (!changed) {
                SELECTION_EXTRA_SETTLE_DELAY.wait()
                changed = probe.find(before, similarity = SELECTION_UNCHANGED_SIMILARITY) == null
            }

            before.close()

            if (changed) return TapResult.Confirmed

            if (command is CommandCard.NP) {
                caster.rejectUse(command)
            }

            if (attempt < SELECTION_CLICK_ATTEMPTS - 1) {
                SELECTION_RETRY_DELAY.wait()
            }
        }

        return TapResult.Failed
    }

    private fun finishSelectionFailure(
        reason: String,
        selectedNps: Collection<CommandCard.NP>
    ): SelectionResult {
        // The third command may have been accepted during an ambiguous probe. Never press Back
        // after the selection page has already disappeared.
        if (!isAttackSelectionScreenVisible()) {
            selectedNps.forEach { caster.confirmUse(it) }
            return SelectionResult.Submitted
        }

        selectedNps.forEach { caster.rejectUse(it) }
        return SelectionResult.NeedsRestart(reason)
    }

    fun clickCommandCards(
        cards: List<ParsedCard>,
        npUsage: NPUsage
    ): SelectionResult {
        val stunned = cards.filter { it.isStunned }.map { it.card }.toSet()
        val facePool = pickCards(cards, npUsage)
            .filterNot { it in stunned }

        val candidates = CommandSelectionPlanner.orderedCandidates(
            faceCards = facePool,
            npUsage = npUsage
        )

        val selectedNps = linkedSetOf<CommandCard.NP>()
        var confirmedCount = 0

        for (command in candidates) {
            if (confirmedCount >= 3) break

            when (command) {
                is CommandCard.Face -> {
                    messages.log(ScriptLog.ClickingCards(listOf(command)))
                    when (tapAndConfirm(command)) {
                        TapResult.Confirmed -> confirmedCount++
                        TapResult.Unavailable -> Unit
                        TapResult.Failed ->
                            return finishSelectionFailure("face-card:$command", selectedNps)
                    }
                }

                is CommandCard.NP -> {
                    messages.log(ScriptLog.ClickingNPs(setOf(command)))
                    when (tapAndConfirm(command)) {
                        TapResult.Confirmed -> {
                            selectedNps += command
                            confirmedCount++
                        }

                        // A second-turn NP probe that is not actually usable must not consume a
                        // command slot. Continue to the next candidate and fill with face cards.
                        TapResult.Unavailable -> Unit

                        // Two no-change attempts are treated as unavailable rather than freezing
                        // the turn. The final three-command submission check below still prevents
                        // false positives from being committed.
                        TapResult.Failed -> Unit
                    }
                }
            }
        }

        if (confirmedCount != 3) {
            return finishSelectionFailure(
                "confirmed-$confirmedCount-of-3",
                selectedNps
            )
        }

        // Do not trust local tap deltas alone. Commit the transaction only after the Attack page
        // actually disappears, which means FGO accepted all three commands.
        repeat(SELECTION_SUBMIT_POLLS) {
            if (!isAttackSelectionScreenVisible()) {
                selectedNps.forEach { caster.confirmUse(it) }
                return SelectionResult.Submitted
            }
            SELECTION_SUBMIT_POLL_DELAY.wait()
        }

        return finishSelectionFailure(
            "three-confirmed-but-attack-still-visible",
            selectedNps
        )
    }

}


internal object CommandSelectionPlanner {
    fun orderedCandidates(
        faceCards: List<CommandCard.Face>,
        npUsage: NPUsage
    ): List<CommandCard> {
        val before = npUsage.cardsBeforeNP.coerceIn(0, 2)
        val nps = npUsage.nps.sortedBy { CommandCard.NP.list.indexOf(it) }

        return buildList {
            addAll(faceCards.take(before))
            addAll(nps)
            addAll(faceCards.drop(before))
        }
    }
}
