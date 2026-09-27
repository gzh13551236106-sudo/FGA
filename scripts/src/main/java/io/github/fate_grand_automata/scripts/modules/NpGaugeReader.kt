package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@ScriptScope
class NpGaugeReader @Inject constructor(
    api: IFgoAutomataApi
) : IFgoAutomataApi by api {
    sealed class Result {
        data class Known(val percent: Int) : Result()
        data object Unknown : Result()
    }

    fun read(slot: FieldSlot): Result {
        repeat(MAX_READS) {
            parse(locations.battle.npValueRegion(slot).detectText(outlinedText = true))?.let {
                return Result.Known(it)
            }
            RETRY_DELAY.wait()
        }
        return Result.Unknown
    }

    internal companion object {
        const val MAX_READS = 3
        val RETRY_DELAY = 200.milliseconds

        fun parse(text: String): Int? {
            val normalized = text.replace('O', '0').replace('o', '0')

            val value = Regex("""(\d{1,3})\s*%""")
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""(?<![A-Za-z])(\d{1,3})(?![A-Za-z])""")
                    .find(normalized)
                    ?.groupValues
                    ?.getOrNull(1)

            return value
                ?.toIntOrNull()
                ?.takeIf { it in 0..300 }
        }
    }
}
