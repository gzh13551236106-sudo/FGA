package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@ScriptScope
class Battle @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
    private val state: BattleState,
    private val battleConfig: IBattleConfig,
    private val autoSkill: AutoSkill,
    private val caster: Caster,
    private val card: Card,
    private val skillSpam: SkillSpam,
    private val shuffleChecker: ShuffleChecker,
    private val stageTracker: StageTracker,
    private val autoChooseTarget: AutoChooseTarget,
    private val recovery: RecoveryWatchdog
) : IFgoAutomataApi by api {
    init {
        prefs.stopAfterThisRun = false
        state.markStartTime()

        resetState()
    }

    fun resetState() {
        // Don't increment no. of runs if we're just clicking on quest again and again
        // This can happen due to lags introduced during some events
        if (state.stage != -1) {
            state.nextRun()

            servantTracker.nextRun()

            prefs.updateCompletedRuns(state.runs)
        }

        if (prefs.stopAfterThisRun) {
            prefs.stopAfterThisRun = false
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.StopAfterThisRun)
        }

        if (prefs.selectedServerConfigPref.shouldLimitRuns && state.runs >= prefs.selectedServerConfigPref.limitRuns) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.LimitRuns(state.runs))
        }
    }

    fun isIdle() = images[Images.BattleScreen] in locations.battle.screenCheckRegion

    fun clickAttack(): CardParser.Result {
        locations.battle.attackClick.click()

        // Wait for Attack button to disappear
        locations.battle.screenCheckRegion.waitVanish(images[Images.BattleScreen], 5.seconds)

        prefs.waitBeforeCards.wait()

        return card.readCommandCards()
    }

    private fun ensureBattlePage(reason: String) {
        if (locations.battle.screenCheckRegion.exists(images[Images.BattleScreen], 1.seconds)) return

        val closeVisible = locations.battle.extraInfoWindowCloseRegion.exists(
            images[Images.Close],
            timeout = 0.25.seconds
        )
        if (closeVisible) {
            locations.battle.extraInfoWindowCloseClick.click()
            if (locations.battle.screenCheckRegion.exists(images[Images.BattleScreen], 1.seconds)) return
        }

        recoveryStop(reason)
    }

    fun performBattle() {
        prefs.waitBeforeTurn.wait()

        onTurnStarted()
        ensureBattlePage("turn-start-left-battle-page")

        if (battleConfig.addRaidTurnDelay){
            battleConfig.raidTurnDelaySeconds.seconds.wait()
        }

        servantTracker.beginTurn()
        ensureBattlePage("servant-scan-left-battle-page")

        val npUsage = autoSkill.execute(state.stage, state.turn)
        skillSpam.spamSkills()

        val cards = readCardsWithRecovery()
            .takeUnless { shouldShuffle(it, npUsage) }
            ?: shuffleCards()

        card.clickCommandCards(cards, npUsage)

        0.5.seconds.wait()
    }

    private fun readCardsWithRecovery(): List<ParsedCard> {
        val first = clickAttack()
        if (first is CardParser.Result.Normal || first is CardParser.Result.Degraded) return first.cards

        // Leave the card screen only when recognition cannot be safely degraded. The shared
        // watchdog budget was already charged by the reads and is not reset by this transition.
        locations.attack.backClick.click()
        if (!locations.battle.screenCheckRegion.exists(images[Images.BattleScreen], 2.seconds)) {
            recoveryStop("card-recovery-battle-page")
        }
        val decision = recovery.request(
            "reenter-attack",
            RecoveryWatchdog.Context(state.stage, state.turn, "read-cards")
        )
        if (decision.level == RecoveryWatchdog.Level.Stop) recoveryStop("card-recovery-budget")
        return when (val recovered = clickAttack()) {
            is CardParser.Result.Normal, is CardParser.Result.Degraded -> recovered.cards
            is CardParser.Result.NeedsRecovery, is CardParser.Result.Unsafe ->
                recoveryStop("command-cards-unsafe")
        }
    }

    private fun recoveryStop(reason: String): Nothing = throw AutoBattle.BattleExitException(
        AutoBattle.ExitReason.RecoveryExhausted(reason)
    )

    private fun shouldShuffle(cards: List<ParsedCard>, npUsage: NPUsage): Boolean {
        // Not this wave
        if (state.stage != (battleConfig.shuffleCardsWave - 1)) {
            return false
        }

        // Already shuffled
        if (state.shuffled) {
            return false
        }

        return shuffleChecker.shouldShuffle(
            mode = battleConfig.shuffleCards,
            cards = cards,
            npUsage = npUsage
        )
    }

    private fun shuffleCards(): List<ParsedCard> {
        locations.attack.backClick.click()

        caster.castMasterSkill(Skill.Master.C)
        state.shuffled = true

        return when (val result = clickAttack()) {
            is CardParser.Result.Normal, is CardParser.Result.Degraded -> result.cards
            is CardParser.Result.NeedsRecovery, is CardParser.Result.Unsafe ->
                recoveryStop("shuffle-command-cards-unsafe")
        }
    }

    private fun onTurnStarted() = useSameSnapIn {
        stageTracker.checkCurrentStage()

        state.nextTurn()

        if (battleConfig.autoChooseTarget) {
            autoChooseTarget.choose()
        }
    }
}
