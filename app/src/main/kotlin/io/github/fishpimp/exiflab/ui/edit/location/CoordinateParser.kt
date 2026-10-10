package io.github.fishpimp.exiflab.ui.edit.location

import io.github.fishpimp.exiflab.ui.map.LatLng
import java.net.URLDecoder
import java.util.Locale

/** A position typed or pasted by the user. [altitudeMeters] comes from `geo:` URIs that carry one. */
data class ParsedPosition(val latLng: LatLng, val altitudeMeters: Double? = null)

/** Why text could not be read as a position. Each maps to a localized message. */
enum class CoordinateError {
    /** Nothing was entered. */
    Empty,

    /** Neither coordinates nor a map link ExifLab understands. */
    Unrecognized,

    /** Numbers were found, but outside ±90 latitude or ±180 longitude, or minutes/seconds of 60 or more. */
    OutOfRange,

    /** A shortened link (maps.app.goo.gl, goo.gl/maps) that only the network can expand. */
    ShortLink,
}

sealed interface CoordinateParse {
    data class Success(val position: ParsedPosition) : CoordinateParse

    data class Failure(val error: CoordinateError) : CoordinateParse
}

/**
 * Reads positions the way people paste them:
 * - decimal degrees: "59.3293, 18.0686", "59,3293 18,0686", "-33.8568 151.2153", "59.3293N 18.0686E";
 * - degrees, minutes and seconds: `59°19'45.5"N 18°4'7.0"E`, "N 59° 19.758' E 18° 4.117'",
 *   "59 19 45.5 N 18 4 7 E" (Swedish Ö/V hemispheres too);
 * - map links: Google Maps (`q=`/`query=`/`ll=`, `!3d…!4d…` place pins, `@lat,lon` views),
 *   `geo:` URIs (with altitude and `?q=`), OpenStreetMap (`mlat`/`mlon`, `#map=zoom/lat/lon`) and
 *   Apple Maps (`ll=`, `q=`).
 *
 * Pure and offline: short links cannot be expanded and are reported as [CoordinateError.ShortLink].
 */
object CoordinateParser {
    fun parse(input: String): CoordinateParse {
        val text = input.trim()
        if (text.isEmpty()) return CoordinateParse.Failure(CoordinateError.Empty)
        if (isShortLink(text)) return CoordinateParse.Failure(CoordinateError.ShortLink)
        if (looksLikeLink(text)) return parseLink(text)
        return parseCoordinates(text)
    }

    /** Convenience for callers that only need the position. */
    fun parseOrNull(input: String): ParsedPosition? = (parse(input) as? CoordinateParse.Success)?.position

    // ---- Links ----

    private val shortLinkHosts = listOf("maps.app.goo.gl", "goo.gl/maps", "g.co/kgs", "osm.org/go/")

    private fun isShortLink(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return shortLinkHosts.any { host -> lower.contains(host) }
    }

    private fun looksLikeLink(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.startsWith("geo:") || lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("www.") || lower.startsWith("maps.") || lower.contains("google.") ||
            lower.contains("openstreetmap.org") || lower.contains("maps.apple.com")
    }

    private val pairPattern = Regex("""([-+]?\d{1,3}(?:\.\d+)?)\s*,\s*([-+]?\d{1,3}(?:\.\d+)?)""")
    private val placePinPattern = Regex("""!3d([-+]?\d{1,3}(?:\.\d+)?)!4d([-+]?\d{1,3}(?:\.\d+)?)""")
    private val atPattern = Regex("""@([-+]?\d{1,3}(?:\.\d+)?),([-+]?\d{1,3}(?:\.\d+)?)""")
    private val osmMapPattern = Regex("""map=\d+(?:\.\d+)?/([-+]?\d{1,3}(?:\.\d+)?)/([-+]?\d{1,3}(?:\.\d+)?)""")

    private fun parseLink(text: String): CoordinateParse {
        val decoded = decode(text)
        val lower = decoded.lowercase(Locale.ROOT)
        val candidates: List<() -> ParsedPosition?> = if (lower.startsWith("geo:")) {
            listOf({ geoUri(decoded) })
        } else {
            listOf(
                // An explicit point beats a place pin, which beats the center of the view.
                { queryPair(decoded, "q") },
                { queryPair(decoded, "query") },
                { queryPair(decoded, "destination") },
                { latLonParams(decoded, "mlat", "mlon") },
                { placePinPattern.find(decoded)?.let { position(it.groupValues[1], it.groupValues[2]) } },
                { queryPair(decoded, "ll") },
                { queryPair(decoded, "sll") },
                { atPattern.find(decoded)?.let { position(it.groupValues[1], it.groupValues[2]) } },
                { osmMapPattern.find(decoded)?.let { position(it.groupValues[1], it.groupValues[2]) } },
                { pathPair(decoded) },
            )
        }
        var outOfRange = false
        for (candidate in candidates) {
            val found = candidate() ?: continue
            if (found.latLng.isValid) return CoordinateParse.Success(found)
            outOfRange = true
        }
        return CoordinateParse.Failure(if (outOfRange) CoordinateError.OutOfRange else CoordinateError.Unrecognized)
    }

    /** `geo:lat,lon[,alt][;u=…][?q=lat,lon(label)]`; `geo:0,0?q=…` means "look at the query". */
    private fun geoUri(text: String): ParsedPosition? {
        val body = text.substringAfter(':')
        val path = body.substringBefore('?').substringBefore(';')
        val parts = path.split(',').map { it.trim() }
        val query = body.substringAfter('?', "")
        val fromQuery = queryValue(query, "q")?.let { pairPattern.find(it) }?.let { position(it.groupValues[1], it.groupValues[2]) }
        val fromPath = if (parts.size >= 2) position(parts[0], parts[1], parts.getOrNull(2)) else null
        val pathIsOrigin = fromPath != null && fromPath.latLng.latitude == 0.0 && fromPath.latLng.longitude == 0.0
        return when {
            fromQuery != null && (fromPath == null || pathIsOrigin) -> fromQuery
            else -> fromPath
        }
    }

    private fun queryPair(text: String, name: String): ParsedPosition? {
        val value = queryValue(text.substringAfter('?', ""), name) ?: queryValue(text.substringAfter('#', ""), name) ?: return null
        val match = pairPattern.find(value) ?: return null
        // Only a value that is nothing but coordinates; "q=Stockholm 59" is a search, not a point.
        if (value.trim().length > match.value.length + 2 && !value.trim().startsWith(match.value)) return null
        return position(match.groupValues[1], match.groupValues[2])
    }

    private fun latLonParams(text: String, latName: String, lonName: String): ParsedPosition? {
        val query = text.substringAfter('?', "").substringBefore('#')
        val lat = queryValue(query, latName) ?: return null
        val lon = queryValue(query, lonName) ?: return null
        return position(lat, lon)
    }

    /** Google's `/maps/search/59.3293,18.0686` and `/maps/place/59.3293,18.0686`. */
    private fun pathPair(text: String): ParsedPosition? {
        val path = text.substringBefore('?').substringBefore('#')
        val segment = path.split('/').lastOrNull { pairPattern.matches(it.trim()) } ?: return null
        val match = pairPattern.find(segment) ?: return null
        return position(match.groupValues[1], match.groupValues[2])
    }

    private fun queryValue(query: String, name: String): String? =
        query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key.equals(name, ignoreCase = true) && part.contains('=')) part.substringAfter('=') else null
        }

    private fun position(lat: String, lon: String, altitude: String? = null): ParsedPosition? {
        val latitude = lat.trim().toDoubleOrNull() ?: return null
        val longitude = lon.trim().toDoubleOrNull() ?: return null
        return ParsedPosition(LatLng(latitude, longitude), altitude?.trim()?.toDoubleOrNull())
    }

    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(text)
            // A '+' that was a space in a query ("59.3,+18.0") reads as a sign otherwise.
            .replace(",+", ",")

    // ---- Coordinates ----

    private enum class Unit { Degrees, Minutes, Seconds }

    private sealed interface Token {
        data class Number(val value: Double, val negative: Boolean, val whole: Boolean, val unit: Unit?) : Token
        data class Hemisphere(val axis: Axis, val negative: Boolean) : Token
        data object Separator : Token
    }

    private enum class Axis { Latitude, Longitude }

    private class AxisValue {
        var prefix: Token.Hemisphere? = null
        var suffix: Token.Hemisphere? = null
        val numbers = mutableListOf<Token.Number>()
        val hemisphere: Token.Hemisphere? get() = prefix ?: suffix
    }

    private fun parseCoordinates(text: String): CoordinateParse {
        val tokens = tokenize(text) ?: return CoordinateParse.Failure(CoordinateError.Unrecognized)
        val numbers = tokens.filterIsInstance<Token.Number>()
        val half = numbers.size / 2
        // "59.3293 18.0686", "59 19.758 18 4.117", "59 19 45 18 4 7": no markers, so the halves are the axes.
        val unmarked = numbers.size == tokens.size && numbers.size in setOf(2, 4, 6) &&
            numbers.none { it.unit != null } &&
            numbers.withIndex().none { (index, number) -> number.negative && index != 0 && index != half }
        val split = if (unmarked) {
            listOf(AxisValue().apply { this.numbers += numbers.take(half) }, AxisValue().apply { this.numbers += numbers.drop(half) })
        } else {
            group(tokens).takeIf { it.size == 2 } ?: return CoordinateParse.Failure(CoordinateError.Unrecognized)
        }
        val values = split.map { axis -> toDegrees(axis) ?: return CoordinateParse.Failure(CoordinateError.OutOfRange) }
        val kinds = split.map { it.hemisphere?.axis }
        val (latitude, longitude) = when {
            kinds[0] == null && kinds[1] == null -> values[0] to values[1]
            kinds[0] == Axis.Longitude && kinds[1] != Axis.Longitude -> values[1] to values[0]
            kinds[1] == Axis.Latitude && kinds[0] != Axis.Latitude -> values[1] to values[0]
            kinds[0] == kinds[1] -> return CoordinateParse.Failure(CoordinateError.Unrecognized)
            else -> values[0] to values[1]
        }
        val latLng = LatLng(latitude, longitude)
        return if (latLng.isValid) {
            CoordinateParse.Success(ParsedPosition(latLng))
        } else {
            CoordinateParse.Failure(CoordinateError.OutOfRange)
        }
    }

    /** Degrees from one axis's numbers, or null when minutes or seconds are out of range. */
    private fun toDegrees(axis: AxisValue): Double? {
        val numbers = axis.numbers
        if (numbers.isEmpty() || numbers.size > 3) return null
        val degrees = numbers[0]
        val minutes = numbers.getOrNull(1)
        val seconds = numbers.getOrNull(2)
        if (minutes != null && (!degrees.whole || minutes.value >= 60)) return null
        if (seconds != null && (!minutes!!.whole || seconds.value >= 60)) return null
        if (numbers.drop(1).any { it.negative }) return null
        val magnitude = degrees.value + (minutes?.value ?: 0.0) / 60 + (seconds?.value ?: 0.0) / 3600
        val negative = axis.hemisphere?.negative ?: degrees.negative
        return if (negative) -magnitude else magnitude
    }

    /** Splits tokens into the two axes; markers (hemispheres, units, separators) decide where one ends. */
    private fun group(tokens: List<Token>): List<AxisValue> {
        val axes = mutableListOf<AxisValue>()
        var current = AxisValue()
        fun close() {
            if (current.numbers.isNotEmpty() || current.prefix != null) axes += current
            current = AxisValue()
        }
        for (token in tokens) {
            when (token) {
                Token.Separator -> close()
                is Token.Hemisphere -> when {
                    current.numbers.isEmpty() -> {
                        if (current.prefix != null) return emptyList()
                        current.prefix = token
                    }
                    current.prefix == null -> {
                        current.suffix = token
                        close()
                    }
                    else -> {
                        close()
                        current.prefix = token
                    }
                }
                is Token.Number -> {
                    val last = current.numbers.lastOrNull()
                    val startsNew = last != null && (
                        current.numbers.size == 3 ||
                            token.unit == Unit.Degrees ||
                            last.unit == Unit.Seconds ||
                            (token.negative)
                        )
                    if (startsNew) close()
                    current.numbers += token
                }
            }
        }
        close()
        return axes
    }

    private val hemispheres = mapOf(
        'N' to Token.Hemisphere(Axis.Latitude, negative = false),
        'S' to Token.Hemisphere(Axis.Latitude, negative = true),
        'E' to Token.Hemisphere(Axis.Longitude, negative = false),
        'Ö' to Token.Hemisphere(Axis.Longitude, negative = false),
        'O' to Token.Hemisphere(Axis.Longitude, negative = false),
        'W' to Token.Hemisphere(Axis.Longitude, negative = true),
        'V' to Token.Hemisphere(Axis.Longitude, negative = true),
    )

    private val degreeMarks = setOf('°', 'º', '˚', 'D')
    private val minuteMarks = setOf('\'', '′', '’', '‘', '`', '´', 'M')
    private val secondMarks = setOf('"', '″', '”', '“')

    /** Tokens of [text], or null when it holds anything that is not part of a coordinate. */
    private fun tokenize(raw: String): List<Token>? {
        val text = raw.uppercase(Locale.ROOT).replace("''", "\"").replace("′′", "″")
        val decimalComma = !text.contains('.') && Regex("""\d,\d""").containsMatchIn(text) &&
            (text.contains(';') || Regex("""\d,\d+\s+[-+]?\d""").containsMatchIn(text) || text.contains(" "))
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || ((c == '-' || c == '+' || c == '−') && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val negative = c == '-' || c == '−'
                    var j = if (c.isDigit()) i else i + 1
                    val start = j
                    while (j < text.length && text[j].isDigit()) j++
                    var whole = true
                    if (j + 1 < text.length && (text[j] == '.' || (decimalComma && text[j] == ',')) && text[j + 1].isDigit()) {
                        whole = false
                        j++
                        while (j < text.length && text[j].isDigit()) j++
                    }
                    val value = text.substring(start, j).replace(',', '.').toDoubleOrNull() ?: return null
                    // A unit mark may follow after spaces: "59 ° 19 ′".
                    var k = j
                    while (k < text.length && text[k] == ' ') k++
                    val unit = when {
                        isMark(text, k, degreeMarks) -> Unit.Degrees
                        isMark(text, k, minuteMarks) -> Unit.Minutes
                        isMark(text, k, secondMarks) -> Unit.Seconds
                        else -> null
                    }
                    if (unit != null) j = k + 1
                    tokens += Token.Number(value, negative, whole, unit)
                    i = j
                }
                c == ',' || c == ';' || c == '/' || c == '|' -> {
                    tokens += Token.Separator
                    i++
                }
                hemispheres.containsKey(c) && !isWordLetter(text, i) -> {
                    tokens += hemispheres.getValue(c)
                    i++
                }
                else -> return null
            }
        }
        return tokens
    }

    /** True when [index] holds one of [marks]; a letter mark ("d", "m") only when it stands alone. */
    private fun isMark(text: String, index: Int, marks: Set<Char>): Boolean {
        val c = text.getOrNull(index) ?: return false
        return c in marks && (!c.isLetter() || !isWordLetter(text, index))
    }

    /** True when the letter at [index] is part of a longer word, so not a lone hemisphere or unit mark. */
    private fun isWordLetter(text: String, index: Int): Boolean {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + 1)
        return (before != null && before.isLetter()) || (after != null && after.isLetter())
    }
}
