package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.OrderChangeMember
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.skills
import io.github.lib_automata.Pattern
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class ServantTracker @Inject constructor(
    api: IFgoAutomataApi,
    private val recovery: RecoveryWatchdog
) : IFgoAutomataApi by api, AutoCloseable {

    private val servantQueue = mutableListOf<TeamSlot>()
    private val _deployed = mutableMapOf<FieldSlot, TeamSlot>()
    val deployed: Map<FieldSlot, TeamSlot> = _deployed

    fun nextRun() {
        servantQueue.clear()
        servantQueue.addAll(
            listOf(TeamSlot.D, TeamSlot.E, TeamSlot.F)
        )

        _deployed.clear()
        _deployed.putAll(
            mapOf(
                FieldSlot.A to TeamSlot.A,
                FieldSlot.B to TeamSlot.B,
                FieldSlot.C to TeamSlot.C
            )
        )
    }

    init {
        nextRun()
    }

    data class TeamSlotData(
        val checkImage: MutableList<Pattern>,
        val skills: List<Pattern>
    ) : AutoCloseable {
        override fun close() {
            checkImage.forEach { it.close() }
            skills.forEach { it.close() }
        }
    }

    val checkImages = mutableMapOf<TeamSlot, TeamSlotData>()
    private var supportSlot: TeamSlot? = null

    private val uOlgaSlots = mutableSetOf<TeamSlot>()

    override fun close() {
        checkImages.values.forEach { it.close() }
        checkImages.clear()
    }

    private fun init(teamSlot: TeamSlot, slot: FieldSlot) {
        messages.log(
            ScriptLog.ServantEnteredSlot(
                servant = teamSlot,
                slot = slot
            )
        )

        var isSupport = false
        // Use only the battle HUD. Never open the servant status/details dialog for identification.
        useSameSnapIn {
            isSupport = isSupport(slot)

            if (teamSlot !in checkImages || isSupport) {
                checkImages[teamSlot] = TeamSlotData(
                    checkImage = mutableListOf(
                        locations.battle.servantChangeCheckRegion(slot)
                            .getPattern("Servant $teamSlot")
                    ),
                    skills = slot.skills().mapIndexed { index, it ->
                        locations.battle.imageRegion(it)
                            .getPattern("Servant $teamSlot S${index + 1}")
                    }
                )
            }

            updateUOlgaIdentity(teamSlot, slot)
        }

        if (supportSlot == null && isSupport) {
            supportSlot = teamSlot
        }
    }

    private fun updateUOlgaIdentity(teamSlot: TeamSlot, slot: FieldSlot) {
        val region = locations.battle.servantChangeSupportCheckRegion(slot)
        val isUOlga = images.loadSupportPattern(SupportImageKind.Servant, U_OLGA_NAME)
            .any { portrait ->
                region.find(portrait, similarity = U_OLGA_BATTLE_SIMILARITY) != null
            }

        if (isUOlga) {
            uOlgaSlots += teamSlot
        } else {
            uOlgaSlots -= teamSlot
        }
    }

    private fun check(slot: FieldSlot) {
        // If a servant is not present, that means none are left in the backline
        if (!locations.battle.servantPresentRegion(slot)
                .exists(images[Images.ServantExist], similarity = 0.70)
        ) {
            _deployed.remove(slot)
            servantQueue.clear()
            return
        }

        val teamSlot = deployed[slot] ?: return
        if (teamSlot is TeamSlot.Unknown) return

        val checkImage = checkImages[teamSlot]?.checkImage

        if (checkImage == null) {
            init(teamSlot, slot)
            return
        }

        val isDifferentServant = checkImage.none { it in locations.battle.servantChangeCheckRegion(slot) }
        val isSupport = isSupport(slot)
        val wasSupport = supportSlot == teamSlot

        // New run with different support
        if (wasSupport && isSupport && isDifferentServant) {
            init(teamSlot, slot)
        } else if (isDifferentServant || (wasSupport != isSupport)) {
            val newTeamSlot = servantQueue.removeFirstOrNull()

            if (newTeamSlot != null) {
                _deployed[slot] = newTeamSlot
                init(newTeamSlot, slot)
            } else {
                // Something has gone wrong with matching servants, a servant is present but we don't know which one
                _deployed[slot] = TeamSlot.Unknown

                messages.log(
                    ScriptLog.ServantEnteredSlot(
                        servant = TeamSlot.Unknown,
                        slot = slot
                    )
                )
            }
        }
    }

    fun beginTurn() =
        FieldSlot.list.forEach {
            check(it)
        }

    fun isUOlga(slot: FieldSlot) = deployed[slot] in uOlgaSlots

    fun orderChanged(starting: OrderChangeMember.Starting, sub: OrderChangeMember.Sub) {
        val startingSlot = when (starting) {
            OrderChangeMember.Starting.A -> FieldSlot.A
            OrderChangeMember.Starting.B -> FieldSlot.B
            OrderChangeMember.Starting.C -> FieldSlot.C
        }
        val subIndex = sub.autoSkillCode - OrderChangeMember.Sub.A.autoSkillCode

        if (subIndex in servantQueue.indices) {
            deployed[startingSlot]?.let { swapOut ->
                _deployed[startingSlot] = servantQueue[subIndex]
                servantQueue[subIndex] = swapOut

                check(startingSlot)
            }
        }
    }

    fun faceCardsGroupedByServant(): Map<TeamSlot, Collection<CommandCard.Face>> {
        if (prefs.skipServantFaceCardCheck) {
            return emptyMap()
        }

        val result = mutableMapOf<TeamSlot, Set<CommandCard.Face>>()

        // Support cards can still be identified from the support marker already present on the
        // command-card screen. Owned-servant grouping is handled visually in CardParser, so no
        // battle overlay needs to be opened.
        supportSlot?.let { supportSlot ->
            if (supportSlot in deployed.values) {
                val matched = CommandCard.Face.list.filter { card ->
                    images[Images.Support] in locations.attack.supportCheckRegion(card)
                }.toSet()

                if (matched.isNotEmpty()) {
                    result[supportSlot] = matched
                }
            }
        }

        result.forEach { (servant, cards) ->
            messages.log(
                ScriptLog.CardsBelongToServant(
                    cards,
                    servant,
                    isSupport = servant == supportSlot
                )
            )
        }

        return result
    }

    /**
     * Adds the 3rd Ascension Melusine image to the existing 1st/2nd Ascension
     * image so both are detected as the same Servant.
     */
    fun melusineChangedAscension(fieldSlot: FieldSlot) {
        val teamSlot = _deployed[fieldSlot]!!
        val teamSlotData = checkImages[teamSlot]
        if (teamSlotData != null && teamSlotData.checkImage.size == 1) {
            teamSlotData.checkImage.add(
                locations.battle.servantChangeCheckRegion(fieldSlot)
                    .getPattern("Melusine Asc3")
            )

            updateUOlgaIdentity(teamSlot, fieldSlot)
        }
    }

    /**
     * Checks if the given [slot] is a Support Servant. Will always return `false` if "Treat Support like own Servant" is enabled.
     */
    private fun isSupport(slot: FieldSlot) = !prefs.treatSupportLikeOwnServant &&
            images[Images.ServantCheckSupport] in locations.battle.servantChangeSupportCheckRegion(slot)

    private companion object {
        const val U_OLGA_NAME = "U-Olga Marie"
        const val U_OLGA_BATTLE_SIMILARITY = 0.72
    }
}
