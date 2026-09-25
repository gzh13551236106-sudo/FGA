package io.github.fate_grand_automata.scripts.modules

import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@ScriptScope
class RecoveryWatchdog internal constructor(
    private val now: () -> Duration,
    private val cancelled: () -> Boolean,
    private val stallTimeout: Duration = 12.seconds,
    private val totalBudget: Int = 8
) {
    @Inject constructor() : this(monotonicClock(), { false })

    enum class Level { Resnapshot, CloseConfirmedDialog, ReturnToStablePage, Stop }
    enum class ActionStatus { NotSent, Sent, Confirmed, Failed, Unknown }

    data class Context(val wave: Int, val turn: Int, val action: String)
    data class Recovery(
        val reason: String,
        val attempt: Int,
        val level: Level,
        val remainingBudget: Int
    )

    private var lastProgressAt = now()
    private var lastUiState: String? = null
    private var budgetUsed = 0
    private val actions = mutableMapOf<Pair<Context, String>, ActionStatus>()

    fun observe(uiState: String?, context: Context): Recovery? {
        checkCancelled()
        if (uiState != null && uiState != lastUiState) {
            lastUiState = uiState
            markProgress()
            return null
        }
        if (now() - lastProgressAt < stallTimeout) return null
        return request("stalled:${uiState ?: "unknown"}", context)
    }

    fun markProgress() {
        lastProgressAt = now()
    }

    fun request(reason: String, context: Context): Recovery {
        checkCancelled()
        if (budgetUsed >= totalBudget) {
            return Recovery(reason, budgetUsed + 1, Level.Stop, 0)
        }
        budgetUsed++
        val level = when {
            budgetUsed <= 2 -> Level.Resnapshot
            budgetUsed <= 5 -> Level.CloseConfirmedDialog
            else -> Level.ReturnToStablePage
        }
        return Recovery(reason, budgetUsed, level, totalBudget - budgetUsed)
    }

    fun actionStatus(context: Context, actionId: String) =
        actions[context to actionId] ?: ActionStatus.NotSent

    fun transitionAction(context: Context, actionId: String, status: ActionStatus) {
        val key = context to actionId
        val current = actions[key] ?: ActionStatus.NotSent
        require(current != ActionStatus.Confirmed || status == ActionStatus.Confirmed) {
            "Confirmed action cannot be sent again"
        }
        require(current != ActionStatus.Sent || status != ActionStatus.Sent) {
            "Sent action cannot be resent before its outcome is known"
        }
        actions[key] = status
        if (status == ActionStatus.Confirmed) markProgress()
    }

    fun canSend(context: Context, actionId: String) =
        actionStatus(context, actionId) in setOf(ActionStatus.NotSent, ActionStatus.Failed)

    fun budgetRemaining() = (totalBudget - budgetUsed).coerceAtLeast(0)

    private fun checkCancelled() {
        if (cancelled()) throw RecoveryCancelledException()
    }

    class RecoveryCancelledException : RuntimeException("Recovery cancelled")

    private companion object {
        fun monotonicClock(): () -> Duration {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow() }
        }
    }
}
