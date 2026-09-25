package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.fate_grand_automata.scripts.modules.SkillRestrictionGuard
import kotlin.test.Test

class SkillRestrictionGuardTest {
    @Test
    fun skipsWhenGameKeepsAttackButtonVisible() {
        assertThat(SkillRestrictionGuard.shouldSkip(attackButtonVanished = false)).isTrue()
    }

    @Test
    fun proceedsWhenGameAcceptsSkill() {
        assertThat(SkillRestrictionGuard.shouldSkip(attackButtonVanished = true)).isFalse()
    }
}
