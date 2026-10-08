package com.mirror.app.phone.roster

/** A non-roster never reaches persistence or replaces the latest private PDF. */
object RosterImportRouting {
    enum class Result { IMPORTED, NOT_ROSTER, FAILED }
    fun route(bytes: ByteArray, firstPageText: (ByteArray) -> String, accept: (ByteArray) -> Boolean): Result {
        if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals("%PDF".toByteArray())) return Result.NOT_ROSTER
        val text = try { firstPageText(bytes) } catch (_: Exception) { return Result.FAILED }
        if (!RosterText.clean(text).contains("Personal Crew Schedule Report", true)) return Result.NOT_ROSTER
        return if (runCatching { accept(bytes) }.getOrDefault(false)) Result.IMPORTED else Result.FAILED
    }
}
