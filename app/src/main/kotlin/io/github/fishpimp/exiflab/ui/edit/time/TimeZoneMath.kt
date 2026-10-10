package io.github.fishpimp.exiflab.ui.edit.time

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * What "Fix time zone" will do to one photo: the recorded wall-clock time read in the camera's
 * zone, and the same instant as a wall-clock time where the photo was taken. Daylight saving is
 * resolved for the photo's own date in both zones.
 *
 * @property shift how far the local time moves (corrected minus recorded).
 * @property recordedInGap the recorded time does not exist in the camera zone (it falls in a
 *   spring-forward gap), so it was read as the time just after the gap.
 * @property recordedAmbiguous the recorded time happens twice in the camera zone (autumn
 *   fall-back); the earlier one was used.
 */
data class TimeZoneCorrection(
    val recorded: LocalDateTime,
    val cameraZone: ZoneId,
    val cameraOffset: ZoneOffset,
    val cameraDaylightSaving: Boolean,
    val actualZone: ZoneId,
    val corrected: LocalDateTime,
    val correctedOffset: ZoneOffset,
    val correctedDaylightSaving: Boolean,
    val shift: Duration,
    val recordedInGap: Boolean,
    val recordedAmbiguous: Boolean,
) {
    val changesTime: Boolean get() = !shift.isZero

    companion object {
        /** The correction for a photo recorded at [recorded] by a camera set to [cameraZone], taken in [actualZone]. */
        fun of(recorded: LocalDateTime, cameraZone: ZoneId, actualZone: ZoneId): TimeZoneCorrection {
            val rules = cameraZone.rules
            val validOffsets = rules.getValidOffsets(recorded)
            val atCamera = recorded.atZone(cameraZone)
            val instant = atCamera.toInstant()
            val atActual = atCamera.withZoneSameInstant(actualZone)
            return TimeZoneCorrection(
                recorded = recorded,
                cameraZone = cameraZone,
                cameraOffset = atCamera.offset,
                cameraDaylightSaving = rules.isDaylightSavings(instant),
                actualZone = actualZone,
                corrected = atActual.toLocalDateTime(),
                correctedOffset = atActual.offset,
                correctedDaylightSaving = actualZone.rules.isDaylightSavings(instant),
                shift = Duration.between(recorded, atActual.toLocalDateTime()),
                recordedInGap = validOffsets.isEmpty(),
                recordedAmbiguous = validOffsets.size > 1,
            )
        }
    }
}

/** One selectable time zone, e.g. Europe/Stockholm: "Stockholm", "Europe", UTC+02:00 on the photo's date. */
data class ZoneChoice(
    val id: ZoneId,
    /** The city or area part of the id, with underscores as spaces: "New York", "Buenos Aires". */
    val city: String,
    /** The continent or ocean part: "Europe", "America/Argentina". Empty for offset-only zones. */
    val region: String,
    /** The zone's offset at the reference time (the photo's capture time when known). */
    val offset: ZoneOffset,
    /** The zone's localized name, e.g. "Central European Time" or "centraleuropeisk tid". */
    val displayName: String,
)

/**
 * Every region-based time zone, for pickers with search. Legacy aliases (US/Pacific, Etc/GMT+5,
 * SystemV/...) are left out; plain offsets are covered by [ZoneOffset] choices elsewhere.
 */
object ZoneCatalog {
    private val regionPrefixes = setOf("Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    /** All zones sorted by offset at [at], then by city, with display names in [locale]. */
    fun all(at: LocalDateTime, locale: Locale): List<ZoneChoice> =
        ZoneId.getAvailableZoneIds()
            .filter { id -> id.substringBefore('/') in regionPrefixes && id.contains('/') }
            .map { id -> choice(ZoneId.of(id), at, locale) }
            .sortedWith(compareBy<ZoneChoice> { it.offset.totalSeconds }.thenBy { it.city })

    fun choice(zone: ZoneId, at: LocalDateTime, locale: Locale): ZoneChoice {
        val id = zone.id
        val city = id.substringAfterLast('/').replace('_', ' ')
        val region = if (id.contains('/')) id.substringBeforeLast('/').replace('_', ' ') else ""
        val offset = zone.rules.getOffset(at)
        val dst = zone.rules.isDaylightSavings(at.atZone(zone).toInstant())
        val displayName = runCatching {
            java.util.TimeZone.getTimeZone(zone).getDisplayName(dst, java.util.TimeZone.LONG, locale)
        }.getOrNull()?.takeUnless { it.startsWith("GMT") } ?: zone.getDisplayName(TextStyle.FULL, locale)
        return ZoneChoice(zone, if (zone is ZoneOffset) id else city, region, offset, displayName)
    }

    /**
     * Zones matching [query] by city, region, id, localized name or offset ("+2", "UTC+05:30",
     * "-3"), best matches first: city prefix, then any word prefix, then anywhere.
     */
    fun search(zones: List<ZoneChoice>, query: String): List<ZoneChoice> {
        val text = query.trim()
        if (text.isEmpty()) return zones
        val offsetQuery = parseOffsetQuery(text)
        val needle = normalize(text)
        return zones.mapNotNull { zone ->
            val city = normalize(zone.city)
            val haystack = normalize("${zone.city} ${zone.region} ${zone.id.id} ${zone.displayName}")
            val rank = when {
                offsetQuery != null && zone.offset == offsetQuery -> 0
                city.startsWith(needle) -> 1
                haystack.split(' ', '/').any { it.startsWith(needle) } -> 2
                haystack.contains(needle) -> 3
                else -> null
            }
            rank?.let { it to zone }
        }.sortedBy { it.first }.map { it.second }
    }

    /**
     * The zone to suggest for "Photo was taken in": [preferred] when it had [offset] at [at],
     * otherwise the first zone in [zones] with that offset whose city is a common choice, otherwise
     * the offset itself. Null when [offset] is unknown.
     */
    fun suggestFor(offset: ZoneOffset?, at: LocalDateTime, preferred: ZoneId, zones: List<ZoneChoice>): ZoneId? {
        offset ?: return null
        if (preferred.rules.getOffset(at) == offset) return preferred
        val matching = zones.filter { it.offset == offset }
        return matching.firstOrNull { it.id.id in wellKnownZones }?.id ?: matching.firstOrNull()?.id ?: offset
    }

    /** "+2", "-03", "UTC+5:30", "GMT-8" as an offset; null for anything else. */
    fun parseOffsetQuery(text: String): ZoneOffset? {
        val match = Regex("""^(?:UTC|GMT)?\s*([+\-−])\s*(\d{1,2})(?::?(\d{2}))?$""", RegexOption.IGNORE_CASE).matchEntire(text.trim()) ?: return null
        val sign = if (match.groupValues[1] == "+") 1 else -1
        val hours = match.groupValues[2].toInt()
        val minutes = match.groupValues[3].ifEmpty { "0" }.toInt()
        return runCatching { ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes) }.getOrNull()
    }

    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    /** Zones people most often mean for an offset, tried first when only the offset is known. */
    private val wellKnownZones = setOf(
        "Europe/London", "Europe/Paris", "Europe/Stockholm", "Europe/Berlin", "Europe/Helsinki", "Europe/Athens",
        "Europe/Moscow", "Asia/Dubai", "Asia/Kolkata", "Asia/Bangkok", "Asia/Shanghai", "Asia/Tokyo", "Australia/Sydney",
        "Pacific/Auckland", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
        "America/Sao_Paulo", "Atlantic/Reykjavik", "Africa/Johannesburg", "Africa/Nairobi", "Pacific/Honolulu",
    )
}

/** Offsets in minutes that are not whole hours but are in real use (India, Nepal, Iran, ...). */
private val commonFractionalOffsets = setOf(-570, -210, 210, 270, 330, 345, 390, 525, 570, 630, 765, 825)

/** Offsets people can choose from when only the offset matters: −12:00 to +14:00, including the half and quarter hours in use. */
val SelectableOffsets: List<ZoneOffset> = buildList {
    for (quarter in -12 * 4..14 * 4) {
        val minutes = quarter * 15
        val minutePart = Math.floorMod(minutes, 60)
        if (minutePart == 0 || minutes in commonFractionalOffsets) add(ZoneOffset.ofTotalSeconds(minutes * 60))
    }
}
