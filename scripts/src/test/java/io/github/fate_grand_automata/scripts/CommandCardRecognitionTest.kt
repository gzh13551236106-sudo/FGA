package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.modules.CommandCardRecognition
import kotlin.test.Test

class CommandCardRecognitionTest {
    private fun cards() = CommandCard.Face.list.map {
        ParsedCard(it, TeamSlot.A, FieldSlot.A, CardTypeEnum.Buster)
    }

    @Test
    fun acceptsFiveRecognizedCards() {
        assertThat(CommandCardRecognition.isReliable(cards(), checkServant = true)).isTrue()
    }

    @Test
    fun rejectsUnknownType() {
        val cards = cards().toMutableList().also {
            it[2] = it[2].copy(type = CardTypeEnum.Unknown)
        }

        assertThat(CommandCardRecognition.isReliable(cards, checkServant = true)).isFalse()
    }

    @Test
    fun allowsUnknownStunnedCard() {
        val cards = cards().toMutableList().also {
            it[2] = it[2].copy(type = CardTypeEnum.Unknown, servant = TeamSlot.Unknown, isStunned = true)
        }

        assertThat(CommandCardRecognition.isReliable(cards, checkServant = true)).isTrue()
    }

    @Test
    fun servantCheckCanBeDisabled() {
        val cards = cards().map { it.copy(servant = TeamSlot.Unknown) }

        assertThat(CommandCardRecognition.isReliable(cards, checkServant = false)).isTrue()
    }
}
