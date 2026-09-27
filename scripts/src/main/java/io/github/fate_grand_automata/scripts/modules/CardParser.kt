package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptNotify
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class CardParser @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker
) : IFgoAutomataApi by api {

    sealed class Result {
        abstract val cards: List<ParsedCard>
        data class Normal(override val cards: List<ParsedCard>) : Result()
        data class Degraded(override val cards: List<ParsedCard>, val reasons: Set<Reason>) : Result()
        data class NeedsRecovery(override val cards: List<ParsedCard>, val reasons: Set<Reason>) : Result()
        data class Unsafe(override val cards: List<ParsedCard>, val reason: Reason) : Result()
    }

    enum class Reason { IncompleteSlots, UnknownServant, UnknownCardType }

    private fun CommandCard.Face.affinity(): CardAffinityEnum {
        val region = locations.attack.affinityRegion(this)

        if (images[Images.Weak] in region) {
            return CardAffinityEnum.Weak
        }

        if (images[Images.Resist] in region) {
            return CardAffinityEnum.Resist
        }

        return CardAffinityEnum.Normal
    }

    private fun CommandCard.Face.isStunned(): Boolean {
        val stunRegion = locations.attack.typeRegion(this).copy(
            y = 930,
            width = 248,
            height = 188
        )

        return listOf(
            images[Images.Stun],
            images[Images.Immobilized],
            images[Images.StunBuster],
            images[Images.StunArts],
            images[Images.StunQuick],
        ) in stunRegion
    }

    private fun CommandCard.Face.type(): CardTypeEnum {
        val region = locations.attack.typeRegion(this)

        if (images[Images.Buster] in region) {
            return CardTypeEnum.Buster
        }

        if (images[Images.Arts] in region) {
            return CardTypeEnum.Arts
        }

        if (images[Images.Quick] in region) {
            return CardTypeEnum.Quick
        }

        return CardTypeEnum.Unknown
    }

    private fun visualGroups(): Map<CommandCard.Face, Int> {
        if (prefs.skipServantFaceCardCheck) return emptyMap()

        val cards = CommandCard.Face.list
        val parent = IntArray(cards.size) { it }

        fun root(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(left: Int, right: Int) {
            val leftRoot = root(left)
            val rightRoot = root(right)
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
        }

        val seeds = cards.associateWith { card ->
            locations.attack.servantIdentitySeedRegion(card)
                .getPattern("CommandCardIdentity:$card")
        }

        try {
            for (i in cards.indices) {
                for (j in i + 1 until cards.size) {
                    val left = cards[i]
                    val right = cards[j]
                    val leftInRight = locations.attack.servantIdentitySearchRegion(right)
                        .find(seeds.getValue(left), VISUAL_GROUP_SIMILARITY) != null
                    val rightInLeft = locations.attack.servantIdentitySearchRegion(left)
                        .find(seeds.getValue(right), VISUAL_GROUP_SIMILARITY) != null

                    if (leftInRight && rightInLeft) {
                        union(i, j)
                    }
                }
            }

            val normalized = mutableMapOf<Int, Int>()
            var nextGroup = 0
            return cards.mapIndexed { index, card ->
                val group = normalized.getOrPut(root(index)) { nextGroup++ }
                card to group
            }.toMap()
        } finally {
            seeds.values.forEach { it.close() }
        }
    }

    fun parse(): List<ParsedCard> {
        val cardsGroupedByServant = servantTracker.faceCardsGroupedByServant()
        val visualGroups = visualGroups()

        val cards = CommandCard.Face.list
            .map {
                val stunned = it.isStunned()
                val type = if (stunned)
                    CardTypeEnum.Unknown
                else it.type()
                val affinity = if (type == CardTypeEnum.Unknown)
                    CardAffinityEnum.Normal // Couldn't detect card type, so don't care about affinity
                else it.affinity()

                val servant = cardsGroupedByServant
                    .filterValues { cards -> it in cards }
                    .keys
                    .firstOrNull()
                    ?: TeamSlot.Unknown

                val fieldSlot = servantTracker.deployed
                    .entries
                    .firstOrNull { (_, teamSlot) -> teamSlot == servant }
                    ?.key

                ParsedCard(
                    card = it,
                    isStunned = stunned,
                    type = type,
                    affinity = affinity,
                    servant = servant,
                    fieldSlot = fieldSlot,
                    visualGroup = visualGroups[it]
                )
            }

        var unknownCardTypes = false
        var unknownServants = false
        val failedToDetermine = cards
            .filter {
                when {
                    it.isStunned -> false
                    it.type == CardTypeEnum.Unknown -> {
                        unknownCardTypes = true
                        true
                    }

                    it.servant is TeamSlot.Unknown &&
                        it.visualGroup == null &&
                        !prefs.skipServantFaceCardCheck -> {
                        unknownServants = true
                        true
                    }

                    else -> false
                }
            }
            .map { it.card }

        if (failedToDetermine.isNotEmpty()) {
            messages.notify(
                ScriptNotify.FailedToDetermineCards(failedToDetermine, unknownCardTypes, unknownServants)
            )
        }

        return cards
    }

    fun classify(cards: List<ParsedCard>) = CommandCardRecognition.classify(
        cards = cards,
        checkServant = !prefs.skipServantFaceCardCheck
    )

    private companion object {
        const val VISUAL_GROUP_SIMILARITY = 0.76
    }
}

internal object CommandCardRecognition {
    fun classify(cards: List<ParsedCard>, checkServant: Boolean): CardParser.Result {
        if (cards.map { it.card }.toSet() != CommandCard.Face.list.toSet()) {
            return CardParser.Result.Unsafe(cards, CardParser.Reason.IncompleteSlots)
        }
        val unknownTypes = cards.count { !it.isStunned && it.type == CardTypeEnum.Unknown }
        val unknownServants = checkServant && cards.any {
            !it.isStunned && it.servant is TeamSlot.Unknown && it.visualGroup == null
        }
        val reasons = buildSet {
            if (unknownTypes > 0) add(CardParser.Reason.UnknownCardType)
            if (unknownServants) add(CardParser.Reason.UnknownServant)
        }
        return when {
            unknownTypes > 1 -> CardParser.Result.NeedsRecovery(cards, reasons)
            reasons.isNotEmpty() -> CardParser.Result.Degraded(cards, reasons)
            else -> CardParser.Result.Normal(cards)
        }
    }
}
