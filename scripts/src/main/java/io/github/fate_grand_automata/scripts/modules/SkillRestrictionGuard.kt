package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Detects skills rejected by FGO after they are tapped (for example U-Olga Marie's third skill
 * below 100% NP), closes the condition dialog, and returns control to the battle loop.
 *
 * Detection is deliberately based on the game's response rather than servant identity. This also
 * makes future skills with special activation conditions safe without maintaining a servant list.
 */
@ScriptScope
class SkillRestrictionGuard @Inject constructor(
    api: IFgoAutomataApi,
    private val state: BattleState,
    private val recovery: RecoveryWatchdog,
    private val servantTracker: ServantTracker,
    private val npGaugeReader: NpGaugeReader
) : IFgoAutomataApi by api {
    enum class Precondition { Allowed, InsufficientNp, UnknownNp }

    private fun closeRestrictionDialogIfPresent(): Boolean {
        val closeVisible = locations.battle.extraInfoWindowCloseRegion.exists(
            images[Images.Close],
            timeout = DIALOG_DETECT_TIMEOUT
        )
        if (closeVisible) {
            locations.battle.extraInfoWindowCloseClick.click()
        }
        return closeVisible
    }

    fun checkBeforeClick(skill: Skill.Servant): Precondition {
        val slot = slotForThirdSkill(skill) ?: return Precondition.Allowed
        if (!servantTracker.isUOlga(slot)) return Precondition.Allowed
        val result = evaluateUOlgaNp(
            (npGaugeReader.read(slot) as? NpGaugeReader.Result.Known)?.percent
        )
        if (result != Precondition.Allowed) {
            messages.log(ScriptLog.SkillPrecondition(skill, result.name))
        }
        return result
    }

    fun accepted(skill: Skill.Servant): Boolean {
        val attackButtonVanished = locations.battle.screenCheckRegion.waitVanish(
            images[Images.BattleScreen],
            1.seconds
        )
        if (!shouldSkip(attackButtonVanished)) return true

        messages.log(ScriptLog.SkillRestricted(skill))
        repeat(MAX_RECOVERY_ATTEMPTS) { index ->
            val decision = recovery.request(
                "skill-restriction",
                RecoveryWatchdog.Context(state.stage, state.turn, skill.toString())
            )
            if (decision.level == RecoveryWatchdog.Level.Stop) return false
            closeRestrictionDialogIfPresent()
            val recovered = locations.battle.screenCheckRegion.exists(
                images[Images.BattleScreen],
                RECOVERY_TIMEOUT
            )
            messages.log(ScriptLog.Recovery("skill-restriction", index + 1, recovered))
            if (recovered) return false
        }

        return false
    }

    fun recoverAfterFailedCast(): Boolean {
        repeat(MAX_RECOVERY_ATTEMPTS) { index ->
            val decision = recovery.request(
                "skill-cast",
                RecoveryWatchdog.Context(state.stage, state.turn, "skill-cast")
            )
            if (decision.level == RecoveryWatchdog.Level.Stop) return false
            closeRestrictionDialogIfPresent()
            val recovered = locations.battle.screenCheckRegion.exists(
                images[Images.BattleScreen],
                RECOVERY_TIMEOUT
            )
            messages.log(ScriptLog.Recovery("skill-cast", index + 1, recovered))
            if (recovered) return true
        }
        return false
    }

    internal companion object {
        const val MAX_RECOVERY_ATTEMPTS = 3
        val RECOVERY_TIMEOUT = 1.seconds
        val DIALOG_DETECT_TIMEOUT = 0.25.seconds

        fun shouldSkip(attackButtonVanished: Boolean) = !attackButtonVanished

        fun evaluateUOlgaNp(percent: Int?) = when {
            percent == null -> Precondition.UnknownNp
            percent >= 100 -> Precondition.Allowed
            else -> Precondition.InsufficientNp
        }

        fun slotForThirdSkill(skill: Skill.Servant) = when (skill) {
            Skill.Servant.A3 -> FieldSlot.A
            Skill.Servant.B3 -> FieldSlot.B
            Skill.Servant.C3 -> FieldSlot.C
            else -> null
        }
    }
}
