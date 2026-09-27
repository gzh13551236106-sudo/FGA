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
import io.github.fate_grand_automata.scripts.models.toFieldSlot
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
    private val recovery: RecoveryWatchdog,
    private val npGaugeReader: NpGaugeReader
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
        val CARD_RECOGNITION_RETRY_DELAY = 300.milliseconds
        val SELECTION_SETTLE_DELAY = 250.milliseconds
        val SELECTION_EXTRA_SETTLE_DELAY = 350.milliseconds
        val SELECTION_RETRY_DELAY = 180.milliseconds
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
     * Resolve NP candidates while the battle HUD is still visible.
     *
     * Known gauges below 100% are excluded. OCR Unknown remains only a candidate; the Attack-page
     * transaction accepts it only when the game visually confirms the NP selection.
     */
    fun prepareNpUsage(requested: NPUsage): NPUsage {
        val candidates = (requested.nps + spamNps)
            .sortedBy { CommandCard.NP.list.indexOf(it) }
            .filter { np ->
                when (val gauge = npGaugeReader.read(np.toFieldSlot())) {
                    is NpGaugeReader.Result.Known -> gauge.percent >= 100
                    NpGaugeReader.Result.Unknown -> true
                }
            }
            .toCollection(linkedSetOf())

        return NPUsage(
            nps = candidates,
            cardsBeforeNP = if (candidates.isEmpty()) 0 else requested.cardsBeforeNP
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
     * A normal/degraded read has at most one unknown card type. Seeing at least two B/A/Q strips
     * therefore means the Attack page is still interactive.
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
                // Slow devices can register the touch before the selected-order overlay is drawn.
                SELECTION_EXTRA_SETTLE_DELAY.wait()
                changed = probe.find(before, similarity = SELECTION_UNCHANGED_SIMILARITY) == null
            }
            before.close()

            if (changed) return TapResult.Confirmed

            if (command is CommandCard.NP) {
                // No visible selection: make the NP action retryable before the one allowed retry.
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
        // If the third input actually completed while the probe was ambiguous, command cards have
        // disappeared. Do not tap Back on live combat.
        if (!isAttackSelectionScreenVisible()) {
            selectedNps.forEach { caster.confirmUse(it) }
            return SelectionResult.Submitted
        }

        // We are definitely still on Attack. The caller may back out and re-enter once.
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

        val selectedFaces = linkedSetOf<CommandCard.Face>()
        val selectedNps = linkedSetOf<CommandCard.NP>()
        var confirmedCount = 0

        fun selectFace(face: CommandCard.Face): SelectionResult? {
            messages.log(ScriptLog.ClickingCards(listOf(face)))
            return when (tapAndConfirm(face)) {
                TapResult.Confirmed -> {
                    selectedFaces += face
                    confirmedCount++
                    null
                }

                TapResult.Unavailable -> null
                TapResult.Failed -> finishSelectionFailure("face-card:$face", selectedNps)
            }
        }

        val cardsBeforeNp = npUsage.cardsBeforeNP.coerceIn(0, 2)
        facePool.take(cardsBeforeNp).forEach { face ->
            if (confirmedCount >= 3) return@forEach
            selectFace(face)?.let { return it }
        }

        npUsage.nps
            .sortedBy { CommandCard.NP.list.indexOf(it) }
            .forEach { np ->
                if (confirmedCount >= 3) return@forEach
                messages.log(ScriptLog.ClickingNPs(setOf(np)))
                when (tapAndConfirm(np)) {
                    TapResult.Confirmed -> {
                        selectedNps += np
                        confirmedCount++
                    }

                    // Disabled/sealed NP: skip it and fill the slot with the next face card.
                    TapResult.Unavailable -> Unit
                    TapResult.Failed ->
                        return finishSelectionFailure("np-card:$np", selectedNps)
                }
            }

        for (face in facePool) {
            if (confirmedCount >= 3) break
            if (face in selectedFaces) continue
            selectFace(face)?.let { return it }
        }

        if (confirmedCount != 3) {
            return finishSelectionFailure(
                "confirmed-$confirmedCount-of-3",
                selectedNps
            )
        }

        selectedNps.forEach { caster.confirmUse(it) }
        return SelectionResult.Submitted
    }

}
