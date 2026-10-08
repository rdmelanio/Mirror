package com.mirror.app.phone.roster

import android.content.Context

/** No Gecko dependency, assets, runtime or session can be loaded in the universal TV build. */
object EcrewFirefox {
    fun create(c: Context, lease: EcrewSessionCoordinator.Lease, background: Boolean = false,
        completed: (Boolean) -> Unit = {}): EcrewEngineBrowser = error("Install mirror-phone.apk for Roster Link")
    fun foregroundOpened(c: Context, anchor: android.view.View) {}
    fun foregroundClosed(anchor: android.view.View) {}
    fun canRefresh(c: Context) = false
    fun clearLocal(c: Context, done: () -> Unit) = done()
}
