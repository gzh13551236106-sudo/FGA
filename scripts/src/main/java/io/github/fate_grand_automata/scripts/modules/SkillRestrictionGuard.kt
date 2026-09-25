package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.models.Skill
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
    api: IFgoAutomataApi
) : IFgoAutomataApi by api {
    fun accepted(skill: Skill.Servant): Boolean {
        val attackButtonVanished = locations.battle.screenCheckRegion.waitVanish(
            images[Images.BattleScreen],
            1.seconds
        )
        if (!shouldSkip(attackButtonVanished)) return true

        messages.log(ScriptLog.SkillRestricted(skill))
        repeat(MAX_RECOVERY_ATTEMPTS) { index ->
            locations.battle.extraInfoWindowCloseClick.click()
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
            locations.battle.extraInfoWindowCloseClick.click()
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

        fun shouldSkip(attackButtonVanished: Boolean) = !attackButtonVanished
    }
}
