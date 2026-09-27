package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsExactly
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.modules.CommandSelectionPlanner
import kotlin.test.Test

class CommandSelectionPlannerTest {
    private val faces = listOf(
        CommandCard.Face.A,
        CommandCard.Face.B,
        CommandCard.Face.C,
        CommandCard.Face.D,
        CommandCard.Face.E
    )

    @Test
    fun npFirstLeavesOnlyTwoFaceSlotsInFirstThreeCandidates() {
        val result = CommandSelectionPlanner.orderedCandidates(
            faceCards = faces,
            npUsage = NPUsage(setOf(CommandCard.NP.A), cardsBeforeNP = 0)
        )

        assertThat(result.take(3)).containsExactly(
            CommandCard.NP.A,
            CommandCard.Face.A,
            CommandCard.Face.B
        )
    }

    @Test
    fun oneFaceBeforeNpKeepsNpInSecondPosition() {
        val result = CommandSelectionPlanner.orderedCandidates(
            faceCards = faces,
            npUsage = NPUsage(setOf(CommandCard.NP.B), cardsBeforeNP = 1)
        )

        assertThat(result.take(3)).containsExactly(
            CommandCard.Face.A,
            CommandCard.NP.B,
            CommandCard.Face.B
        )
    }

    @Test
    fun twoNpsConsumeTwoOfThreeCommandSlots() {
        val result = CommandSelectionPlanner.orderedCandidates(
            faceCards = faces,
            npUsage = NPUsage(
                linkedSetOf(CommandCard.NP.A, CommandCard.NP.B),
                cardsBeforeNP = 0
            )
        )

        assertThat(result.take(3)).containsExactly(
            CommandCard.NP.A,
            CommandCard.NP.B,
            CommandCard.Face.A
        )
    }
}
