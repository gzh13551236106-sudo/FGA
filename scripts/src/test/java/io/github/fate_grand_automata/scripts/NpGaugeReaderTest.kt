package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.modules.NpGaugeReader
import io.github.fate_grand_automata.scripts.modules.SkillRestrictionGuard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.Skill
import kotlin.test.Test

class NpGaugeReaderTest {
    @Test fun parsesGaugeValues() {
        assertThat(NpGaugeReader.parse("NP 99% ")).isEqualTo(99)
        assertThat(NpGaugeReader.parse("100% ")).isEqualTo(100)
        assertThat(NpGaugeReader.parse(" 120 %")).isEqualTo(120)
        assertThat(NpGaugeReader.parse("unknown")).isNull()
    }

    @Test fun uOlgaThresholdIsConservative() {
        assertThat(SkillRestrictionGuard.evaluateUOlgaNp(99)).isEqualTo(SkillRestrictionGuard.Precondition.InsufficientNp)
        assertThat(SkillRestrictionGuard.evaluateUOlgaNp(100)).isEqualTo(SkillRestrictionGuard.Precondition.Allowed)
        assertThat(SkillRestrictionGuard.evaluateUOlgaNp(120)).isEqualTo(SkillRestrictionGuard.Precondition.Allowed)
        assertThat(SkillRestrictionGuard.evaluateUOlgaNp(null)).isEqualTo(SkillRestrictionGuard.Precondition.UnknownNp)
    }

    @Test fun onlyThirdSkillUsesItsCurrentFieldSlot() {
        assertThat(SkillRestrictionGuard.slotForThirdSkill(Skill.Servant.A3)).isEqualTo(FieldSlot.A)
        assertThat(SkillRestrictionGuard.slotForThirdSkill(Skill.Servant.B3)).isEqualTo(FieldSlot.B)
        assertThat(SkillRestrictionGuard.slotForThirdSkill(Skill.Servant.C3)).isEqualTo(FieldSlot.C)
        assertThat(SkillRestrictionGuard.slotForThirdSkill(Skill.Servant.A2)).isNull()
    }
}
