package com.mirror.app.phone.roster
internal object CrewImportPolicy {
    fun allowed(existing:String?, fresh:String, interactive:Boolean, confirmedFrom:String?):Boolean =
        existing==null || existing==fresh || (interactive && confirmedFrom==existing)
}
