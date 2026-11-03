package com.mirror.app.phone

/** A failed direct route is quarantined for this service lifetime. Fallback reuses the owned YUV frame. */
internal class AirInputPolicy(private val report:()->Unit={}) {
    var bitmapFallback=false;private set
    companion object {
        fun rejection(e:RuntimeException)=e is IllegalArgumentException && e.message.orEmpty().let { it.contains("buffer",true) || it.contains("image",true) }
    }
    fun reset() { bitmapFallback=false }
    fun <I:AutoCloseable,R> process(mode:String,build:(Boolean)->I,use:(I)->R):R {
        val bitmap=mode=="bitmap" || bitmapFallback
        var constructing=true
        try {
            return build(bitmap).use { constructing=false;use(it) }
        } catch(e:RuntimeException) {
            val inputError=constructing || rejection(e)
            if(bitmap || !inputError) throw e
            bitmapFallback=true;report()
            return build(true).use(use)
        }
    }
}
