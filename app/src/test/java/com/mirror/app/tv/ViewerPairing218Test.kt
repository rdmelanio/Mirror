package com.mirror.app.tv

import android.content.SharedPreferences
import com.mirror.app.core.PairingGeneration
import com.mirror.app.phone.MjpegServer
import com.mirror.app.phone.PairingAuthority
import com.mirror.app.phone.StreamStats
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.InetAddress

class ViewerPairing218Test {
    private class MemoryPreferences {
        val values=mutableMapOf<String,String>()
        var failNext=false
        val prefs:SharedPreferences=Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)) { _,m,args ->
            when(m.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference method ${m.name}")
            }
        } as SharedPreferences
        private fun editor():SharedPreferences.Editor {
            val pending=mutableMapOf<String,String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,m,args ->
                when(m.name) {
                    "putString" -> { pending[args!![0] as String]=args[1] as String?;proxy }
                    "remove" -> { pending[args!![0] as String]=null;proxy }
                    "commit" -> {
                        pending.forEach { (k,v)->if(v==null) values.remove(k) else values[k]=v }
                        val success=!failNext;failNext=false;success
                    }
                    else -> error("Unexpected editor method ${m.name}")
                }
            } as SharedPreferences.Editor
        }
    }
    @Test fun networkPairingDoesNotSaveUntilCurrentScreenCommits() {
        val memory=MemoryPreferences();val pairing=ViewerPairing(memory.prefs)
        val authority=PairingAuthority();val code=authority.showCode()!!
        val server=MjpegServer("Camera",StreamStats(),authority,InetAddress.getByName("127.0.0.1"),allowAddress={it.isLoopbackAddress},listenPort=0)
        server.start()
        try {
            val camera=ViewerPairing.Camera(authority.cameraId,"Camera");val gate=PairingGeneration();val old=gate.begin()
            val token=pairing.pair("http://127.0.0.1:${server.port}/video",camera,code,"TV")
            assertNull(pairing.token(camera.id))
            val current=gate.begin()
            assertFalse(gate.commit(old) { pairing.commit(camera,token) });assertNull(pairing.token(camera.id))
            assertTrue(gate.commit(current) { pairing.commit(camera,token) });assertEquals(token,pairing.token(camera.id))
        } finally { server.close() }
    }
    @Test fun failedDiskCommitRestoresPreviousInMemoryToken() {
        val memory=MemoryPreferences();val pairing=ViewerPairing(memory.prefs);val camera=ViewerPairing.Camera("camera","Camera")
        assertTrue(pairing.commit(camera,"a".repeat(43)))
        memory.failNext=true
        assertFalse(pairing.commit(camera,"b".repeat(43)))
        assertEquals("a".repeat(43),pairing.token(camera.id))
    }
}
