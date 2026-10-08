package com.mirror.app.phone.roster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.widget.Toast
import org.json.*

/** The only script call is an explicit Run probe tap on a Dashboard. No bridge, hooks or polling. */
object EcrewProbe {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private data class Pending(val web: WebView, val prefix: String, val c: Context, val id: String, val mode: String, var initial: JSONObject? = null, var timeout: Runnable? = null)
    private var pending: Pending? = null
    fun runFromTap(c: Context, web: WebView, id: String, mode: String) {
        if (!EcrewProbePolicy.allowed(true, web.url)) { Toast.makeText(c, "Run probe on an eCrew Dashboard page", Toast.LENGTH_LONG).show(); return }
        if (pending != null) { Toast.makeText(c, "Probe already running", Toast.LENGTH_SHORT).show(); return }
        val p = Pending(web, "MIRROR_PROBE_${java.util.UUID.randomUUID()}:", c.applicationContext, id, mode)
        pending = p
        p.timeout = Runnable { if (pending === p) finish(p, p.initial ?: JSONObject(), "result timeout") }
        main.postDelayed(p.timeout!!, 15_000)
        web.evaluateJavascript(script(p.prefix)) { encoded ->
            if (pending !== p) return@evaluateJavascript
            val raw = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
            val initial = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            p.initial = initial
            if (initial?.optString("error") == "not_dashboard") finish(p, JSONObject(), "page changed; probe skipped")
        }
    }
    fun console(message: ConsoleMessage, currentUrl: String?): Boolean {
        val p = pending ?: return false
        if (!message.message().startsWith(p.prefix)) return false
        // A navigation to Login ends the probe without retaining its console/body contents.
        if (!EcrewProbePolicy.dashboard(currentUrl)) { finish(p, p.initial ?: JSONObject(), "page left Dashboard"); return true }
        val raw = message.message().removePrefix(p.prefix)
        val value = if (raw.length <= 32_000) runCatching { JSONObject(raw) }.getOrNull() else null
        finish(p, value ?: JSONObject(), if (value == null) "invalid result" else null)
        return true
    }
    fun cancel(web: WebView) {
        val p = pending?.takeIf { it.web === web } ?: return
        finish(p, p.initial ?: JSONObject(), "browser closed")
    }
    private fun finish(p: Pending, value: JSONObject, error: String?) {
        if (pending !== p) return
        pending = null; p.timeout?.let { main.removeCallbacks(it) }
        val safe = redact(value)
        if (error != null) safe.put("result", error)
        CaptureLog.add(p.c, "PROBE", "${p.id} ${p.mode} INTERACTIVE $safe")
    }
    internal fun redact(value: JSONObject): JSONObject {
        fun count(j: JSONObject?, key: String) = (j?.optInt(key, 0) ?: 0).coerceIn(0, 1_000_000)
        fun names(a: JSONArray?) = JSONArray().apply { if (a != null) for (i in 0 until minOf(a.length(), 128)) put(EcrewProbePolicy.name(a.optString(i))) }
        val safe = JSONObject()
        for (key in listOf("sessionStorage", "localStorage")) {
            val j = value.optJSONObject(key)
            safe.put(key, JSONObject().put("length", count(j, "length")).put("keys", names(j?.optJSONArray("keys"))).put("available", j?.optBoolean("available", false) == true))
        }
        val tab = value.optJSONObject("eCrewTabID"); val masked = tab?.optString("masked").orEmpty()
        safe.put("eCrewTabID", JSONObject().put("present", tab?.optBoolean("present", false) == true).put("length", count(tab, "length"))
            .put("masked", if (masked.length <= 7 && masked.contains('…')) masked.replace('\n', ' ').replace('\r', ' ') else "…"))
        val token = value.optJSONObject("verificationToken")
        safe.put("verificationToken", JSONObject().put("count", count(token, "count")).put("length", count(token, "length")))
        val cookies = value.optJSONObject("cookies")
        safe.put("cookies", JSONObject().put("count", count(cookies, "count")).put("names", names(cookies?.optJSONArray("names"))))
        safe.put("userAgent", value.optString("userAgent").replace('\n', ' ').replace('\r', ' ').take(512))
        safe.put("brands", JSONArray().apply { value.optJSONArray("brands")?.let { a ->
            for (i in 0 until minOf(a.length(), 16)) put(a.optString(i).replace('\n', ' ').replace('\r', ' ').replace(Regex("[A-Za-z0-9]{32,}"), "…").take(64))
        } })
        val frames = value.optJSONObject("iframes")
        safe.put("iframes", JSONObject().put("count", count(frames, "count")).put("paths", JSONArray().apply {
            frames?.optJSONArray("paths")?.let { a -> for (i in 0 until minOf(a.length(), 64)) put(EcrewLogRedaction.path(a.optString(i))) }
        }))
        val xhr = value.optJSONObject("xhr")
        safe.put("xhr", JSONObject().put("status", count(xhr, "status")).put("contentType", xhr?.optString("contentType").orEmpty().substringBefore(';').take(80))
            .put("body", if (xhr?.optBoolean("loginResponse", false) == true) "[Login response omitted]" else EcrewProbePolicy.body(xhr?.optString("body").orEmpty()))
            .put("loginResponse", xhr?.optBoolean("loginResponse", false) == true))
        return safe
    }
    internal fun script(prefix: String) = """(function(){
        if(location.origin!=='https://ecrew.cebupacificair.com'||!/^\/eCrew\/Dashboard(?:\/|$)/i.test(location.pathname))return JSON.stringify({error:'not_dashboard'});
        const name=v=>/^[A-Za-z_][A-Za-z0-9_.-]{0,63}$/.test(v)&&!/[0-9]{6,}|[A-Za-z0-9]{32,}/.test(v)?v:'[name omitted]';
        const store=get=>{try{const s=get();return {available:true,length:s.length,keys:Array.from({length:Math.min(s.length,128)},(_,i)=>name(s.key(i)||''))};}catch(e){return {available:false,length:0,keys:[]};}};
        const sample=v=>String(v).slice(0,300).replace(/[A-Za-z0-9]{20,}/g,'…').replace(/(['"])[^'"]*['"]/g,'"…"').replace(/[\p{L}\p{N}_@.+-]+/gu,v=>new Set('html head body title meta div p span input script style form type name content class id error status code message bad request invalid verification token session expired terminated another active currently open under your account this has now been unauthorized forbidden null true false'.split(' ')).has(v.toLowerCase())?v:'…');
        const path=u=>{try{return new URL(u,location.href).pathname.split('/').map(v=>{let d=decodeURIComponent(v);return d.length>24||(d.match(/[0-9]/g)||[]).length>=6||/[?&#=;\\]/.test(d)?'*':d;}).join('/');}catch(e){return '[unavailable]';}};
        let tab=null;try{tab=sessionStorage.getItem('eCrewTabID');}catch(e){}
        const tokens=document.querySelectorAll('input[name="__RequestVerificationToken"]');
        const cookieNames=document.cookie.split(';').map(v=>v.split('=')[0].trim()).filter(Boolean).map(name);
        const frames=Array.from(document.querySelectorAll('iframe'));
        const result={sessionStorage:store(()=>sessionStorage),localStorage:store(()=>localStorage),eCrewTabID:{present:tab!==null,length:tab===null?0:tab.length,masked:tab!==null&&tab.length>6?tab.slice(0,4)+'…'+tab.slice(-2):'…'},verificationToken:{count:tokens.length,length:tokens.length?tokens[0].value.length:0},cookies:{count:cookieNames.length,names:cookieNames},userAgent:navigator.userAgent,brands:(navigator.userAgentData?.brands||[]).map(v=>v.brand),iframes:{count:frames.length,paths:frames.map(f=>path(f.getAttribute('src')||''))},xhr:{status:0,contentType:'',body:''}};
        let sent=false;const done=()=>{if(sent)return;sent=true;console.info(${JSONObject.quote(prefix)}+JSON.stringify(result));};
        try{const xhr=new XMLHttpRequest();xhr.open('GET','/eCrew/Dashboard/HomeIndex',true);xhr.timeout=10000;
          xhr.onload=()=>{let login=false;try{login=/\/Login(?:\/|$)/i.test(new URL(xhr.responseURL,location.href).pathname);}catch(e){}result.xhr={status:xhr.status,contentType:(xhr.getResponseHeader('content-type')||'').split(';')[0],loginResponse:login,body:login?'':sample(xhr.responseText||'')};done();};
          xhr.onerror=xhr.ontimeout=xhr.onabort=done;xhr.send();
        }catch(e){done();}
        return JSON.stringify(result);
    })()""".trimIndent()
}
