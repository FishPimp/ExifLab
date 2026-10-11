package io.github.fishpimp.exiflab.ui.edit.time

import androidx.compose.runtime.Immutable
import java.time.Duration

/**
 * The amount entered in "Shift time": whole days, hours, minutes and seconds, moved [later] or
 * earlier. Each part is kept separately so the steppers show what the user chose.
 */
@Immutable
data class TimeShift(
    val days: Int = 0,
    val hours: Int = 0,
    val minutes: Int = 0,
    val seconds: Int = 0,
    val later: Boolean = true,
) {
    val duration: Duration
        get() {
            val magnitude = Duration.ofDays(days.toLong()).plusHours(hours.toLong()).plusMinutes(minutes.toLong()).plusSeconds(seconds.toLong())
            return if (later) magnitude else magnitude.negated()
        }

    val isZero: Boolean get() = days == 0 && hours == 0 && minutes == 0 && seconds == 0

    fun with(unit: ShiftUnit, value: Int): TimeShift {
        val clamped = value.coerceIn(0, unit.max)
        return when (unit) {
            ShiftUnit.Days -> copy(days = clamped)
            ShiftUnit.Hours -> copy(hours = clamped)
            ShiftUnit.Minutes -> copy(minutes = clamped)
            ShiftUnit.Seconds -> copy(seconds = clamped)
        }
    }

    fun value(unit: ShiftUnit): Int = when (unit) {
        ShiftUnit.Days -> days
        ShiftUnit.Hours -> hours
        ShiftUnit.Minutes -> minutes
        ShiftUnit.Seconds -> seconds
    }

    companion object {
        /** Splits [duration] back into parts, e.g. to edit a shift that is already queued. */
        fun of(duration: Duration): TimeShift {
            val later = !duration.isNegative
            val total = duration.abs()
            return TimeShift(
                days = total.toDays().toInt().coerceAtMost(ShiftUnit.Days.max),
                hours = total.hoursPart(),
                minutes = total.minutesPart(),
                seconds = total.secondsPart(),
                later = later,
            )
        }
    }
}

/** The parts of a [TimeShift], with how far each stepper goes. */
enum class ShiftUnit(val max: Int) {
    Days(9_999),
    Hours(23),
    Minutes(59),
    Seconds(59),
}

// Duration's toHoursPart() and friends need API 31; the minimum is 29. These take a non-negative duration.

/** Hours past the whole days, 0..23. */
fun Duration.hoursPart(): Int = ((seconds / SECONDS_PER_HOUR) % HOURS_PER_DAY).toInt()

/** Minutes past the whole hours, 0..59. */
fun Duration.minutesPart(): Int = ((seconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR).toInt()

/** Seconds past the whole minutes, 0..59. */
fun Duration.secondsPart(): Int = (seconds % SECONDS_PER_MINUTE).toInt()

private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val HOURS_PER_DAY = 24L
