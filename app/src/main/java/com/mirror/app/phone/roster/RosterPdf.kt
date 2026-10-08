package com.mirror.app.phone.roster

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.time.*
import java.time.format.DateTimeFormatter

object RosterPdf {
    private val date = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    fun parse(context: Context, bytes: ByteArray): Roster {
        require(bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals("%PDF".toByteArray()))
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(bytes).use { doc ->
            require(doc.numberOfPages in 1..20)
            val pages = mutableListOf<List<Word>>()
            val texts = mutableListOf<String>()
            for (page in 1..doc.numberOfPages) {
                val words = mutableListOf<Word>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
                        // Group adjacent glyphs into words; Unicode fonts often split every character.
                        var group = mutableListOf<TextPosition>()
                        fun flush() {
                            if (group.isNotEmpty()) {
                                val value = RosterText.clean(group.joinToString("") { it.unicode })
                                if (value.isNotEmpty()) words += Word(value, group.minOf { it.xDirAdj }, group.maxOf { it.xDirAdj + it.widthDirAdj }, group.minOf { it.yDirAdj })
                                group = mutableListOf()
                            }
                        }
                        positions.forEach { pos ->
                            if (pos.unicode.isBlank()) flush() else {
                                val prior = group.lastOrNull()
                                if (prior != null && (kotlin.math.abs(pos.yDirAdj - prior.yDirAdj) > 2 || pos.xDirAdj - prior.xDirAdj - prior.widthDirAdj > maxOf(1.5f, pos.widthOfSpace * 0.45f))) flush()
                                group += pos
                            }
                        }
                        flush(); super.writeString(text, positions)
                    }
                }.apply { startPage = page; endPage = page; sortByPosition = true }
                texts += RosterText.clean(stripper.getText(doc)); pages += words
            }
            val all = texts.joinToString("\n")
            require(all.contains("Personal Crew Schedule Report", true))
            val periodMatch = Regex("(\\d{2}/\\d{2}/\\d{4})\\s*-\\s*(\\d{2}/\\d{2}/\\d{4})").find(all) ?: error("Period missing")
            val period = Period(LocalDate.parse(periodMatch.groupValues[1], date), LocalDate.parse(periodMatch.groupValues[2], date))
            require(!period.end.isBefore(period.start) && period.end <= period.start.plusDays(62))
            val crew = Regex("(?m)^\\s*(\\d+)\\s+[^\n,]+,\\s+[^\n]+\\s+[A-Z]{3}-[A-Z0-9]+-[A-Z0-9]+").find(texts.first())?.groupValues?.get(1) ?: error("Crew ID missing")
            val columns = sortedMapOf<LocalDate, MutableList<String>>()
            pages.forEach { words ->
                val candidates = words.filter { Regex("^\\d{2}/\\d{2}$").matches(it.text) }
                val row = candidates.groupBy { (it.yMin / 3).toInt() }.values.maxByOrNull { it.size }.orEmpty()
                if (row.size < 2) return@forEach
                val headerY = row.map { it.yMin }.average().toFloat()
                val weekday = words.filter { it.text in setOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun") && it.yMin > headerY && it.yMin < headerY + 30 }.maxOfOrNull { it.yMin } ?: error("Weekday row missing")
                val bottom = words.filter { it.text == "Total" && it.yMin > weekday }.minOfOrNull { it.yMin } ?: Float.MAX_VALUE
                val headers = row.sortedBy { it.xMin }
                val bins = RosterText.bin(words, headers, weekday, bottom)
                headers.forEachIndexed { index, h ->
                    var d = period.start
                    while (d <= period.end && d.format(DateTimeFormatter.ofPattern("dd/MM")) != h.text) d = d.plusDays(1)
                    require(d <= period.end)
                    columns.getOrPut(d) { mutableListOf() }.addAll(bins[index])
                }
            }
            require(columns.isNotEmpty()) { "No roster columns" }
            val legendSection = all.substringAfter("Descriptions", "")
            val legend = Regex("(?m)^\\s*([A-Z]{2,5})\\s*[-–]\\s*(.+)$").findAll(legendSection).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val memoSection = all.substringAfter("Memos", "").substringBefore("Descriptions")
            val memos = mutableMapOf<LocalDate, String>()
            var memoDate: LocalDate? = null
            memoSection.lines().forEach { line ->
                val match = Regex("^\\s*(\\d{2}/\\d{2}(?:/\\d{4})?)\\s+(.+)$").find(line)
                if (match != null) {
                    val raw = match.groupValues[1]
                    val day = if (raw.length == 10) LocalDate.parse(raw, date) else {
                        var d = period.start; while (d <= period.end && d.format(DateTimeFormatter.ofPattern("dd/MM")) != raw) d = d.plusDays(1); d
                    }
                    if (day in period.start..period.end) { memoDate = day; memos[day] = match.groupValues[2].trim() }
                } else if (memoDate != null && line.isNotBlank() && !line.contains("Personal Crew") && !line.contains("Category")) {
                    memos[memoDate!!] = memos[memoDate!!].orEmpty() + "\n" + line.trim()
                }
            }
            val duties = RosterGrammar.parse(columns) { CaptureLog.add(context, "PARSE", "unknown airport timezone; using Asia/Manila") }
                .map { it.copy(memo = memos[it.date]) }
            require(duties.isNotEmpty()) { "No duties" }
            Roster(period, crew, Instant.now(), duties, memos, legend)
        }
    }
}
