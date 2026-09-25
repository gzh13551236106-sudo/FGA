package io.github.fate_grand_automata.scripts

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.modules.RecoveryWatchdog
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RecoveryWatchdogTest {
    private val context = RecoveryWatchdog.Context(1, 2, "test")

    @Test fun unchangedUiStallsButStateChangeResetsProgress() {
        var now = Duration.ZERO
        val watchdog = RecoveryWatchdog({ now }, { false }, 10.seconds, 3)
        assertThat(watchdog.observe("battle", context)).isNull()
        now += 9.seconds
        assertThat(watchdog.observe("battle", context)).isNull()
        assertThat(watchdog.observe("support", context)).isNull()
        now += 9.seconds
        assertThat(watchdog.observe("support", context)).isNull()
        now += 2.seconds
        assertThat(watchdog.observe("support", context)?.reason).isEqualTo("stalled:support")
    }

    @Test fun budgetIsSharedAndEventuallyStops() {
        val watchdog = RecoveryWatchdog({ Duration.ZERO }, { false }, totalBudget = 2)
        watchdog.request("details", context)
        watchdog.markProgress()
        watchdog.request("cards", context)
        assertThat(watchdog.request("skill", context).level).isEqualTo(RecoveryWatchdog.Level.Stop)
    }

    @Test fun unknownUiNeverProducesClickLevel() {
        var now = Duration.ZERO
        val watchdog = RecoveryWatchdog({ now }, { false }, 10.seconds, 3)
        now = 20.seconds
        val result = watchdog.observe(null, context)!!
        assertThat(result.level).isEqualTo(RecoveryWatchdog.Level.Resnapshot)
    }

    @Test fun sentActionCannotBeBlindlyResent() {
        val watchdog = RecoveryWatchdog({ Duration.ZERO }, { false })
        watchdog.transitionAction(context, "skill", RecoveryWatchdog.ActionStatus.Sent)
        assertThat(watchdog.canSend(context, "skill")).isEqualTo(false)
        assertFailure { watchdog.transitionAction(context, "skill", RecoveryWatchdog.ActionStatus.Sent) }
    }

    @Test fun cancellationInterruptsRecovery() {
        val watchdog = RecoveryWatchdog({ Duration.ZERO }, { true })
        assertFailure { watchdog.request("cards", context) }
            .isInstanceOf(RecoveryWatchdog.RecoveryCancelledException::class)
    }
}
