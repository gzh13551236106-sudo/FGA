package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isInstanceOf
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.modules.CardParser
import io.github.fate_grand_automata.scripts.modules.CommandCardRecognition
import kotlin.test.Test

class CommandCardRecognitionTest {
    private fun cards() = CommandCard.Face.list.map {
        ParsedCard(it, TeamSlot.A, FieldSlot.A, CardTypeEnum.Buster)
    }

    @Test fun completeCardsAreNormal() = assertThat(
        CommandCardRecognition.classify(cards(), true)
    ).isInstanceOf(CardParser.Result.Normal::class)

    @Test fun unknownServantIsDegraded() = assertThat(
        CommandCardRecognition.classify(cards().map { it.copy(servant = TeamSlot.Unknown, fieldSlot = null) }, true)
    ).isInstanceOf(CardParser.Result.Degraded::class)

    @Test fun oneUnknownTypeIsDegraded() = assertThat(
        CommandCardRecognition.classify(cards().mapIndexed { i, card ->
            if (i == 2) card.copy(type = CardTypeEnum.Unknown) else card
        }, true)
    ).isInstanceOf(CardParser.Result.Degraded::class)

    @Test fun multipleUnknownTypesNeedRecovery() = assertThat(
        CommandCardRecognition.classify(cards().mapIndexed { i, card ->
            if (i < 2) card.copy(type = CardTypeEnum.Unknown) else card
        }, true)
    ).isInstanceOf(CardParser.Result.NeedsRecovery::class)

    @Test fun stunnedUnknownCardIsNormal() = assertThat(
        CommandCardRecognition.classify(cards().mapIndexed { i, card ->
            if (i == 2) card.copy(type = CardTypeEnum.Unknown, servant = TeamSlot.Unknown, isStunned = true) else card
        }, true)
    ).isInstanceOf(CardParser.Result.Normal::class)

    @Test fun incompleteSlotsAreUnsafe() = assertThat(
        CommandCardRecognition.classify(cards().dropLast(1), true)
    ).isInstanceOf(CardParser.Result.Unsafe::class)
}
