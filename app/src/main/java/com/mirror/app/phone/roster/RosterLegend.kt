package com.mirror.app.phone.roster

/** PDF extraction may preserve column gaps or collapse them to single spaces. */
object RosterLegend {
    fun parse(text: String): Map<String, String> = buildMap {
        val entry = Regex("(?:^|\\s+)([A-Z]{1,5})\\s*[-–]\\s+")
        text.lineSequence().forEach { line ->
            val matches = entry.findAll(line).toList()
            matches.forEachIndexed { index, match ->
                val end = matches.getOrNull(index + 1)?.range?.first ?: line.length
                val label = line.substring(match.range.last + 1, end).trim().split(Regex("\\s{2,}"), limit = 2).first().trim()
                if (label.isNotBlank()) put(match.groupValues[1], label)
            }
        }
    }
}
