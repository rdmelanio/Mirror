package com.mirror.app.phone.roster
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class RosterSpilloverTest {
 @Test fun adjacentCalendarDatesDoNotChangeDeclaredPeriod() {
  val p=Period(LocalDate.of(2026,11,1),LocalDate.of(2026,11,30))
  assertEquals(LocalDate.of(2026,12,1),RosterCalendarDates.resolve(p,"01/12"))
  assertEquals(LocalDate.of(2027,1,1),RosterCalendarDates.resolve(Period(LocalDate.of(2026,12,1),LocalDate.of(2026,12,31)),"01/01"))
  assertEquals(LocalDate.of(2028,2,29),RosterCalendarDates.resolve(Period(LocalDate.of(2028,2,1),LocalDate.of(2028,2,28)),"29/02"))
  try { RosterCalendarDates.resolve(p,"15/12");fail() } catch (_:IllegalArgumentException) {}
 }
 @Test fun privateLayoutVerificationWhenExplicitlyProvided() {
  val path=System.getenv("MIRROR_PRIVATE_LAYOUT")
  val synthetic=org.json.JSONObject().put("texts",org.json.JSONArray().put("Personal Crew Schedule Report\n01/11/2026 - 30/11/2026\n000000 SYNTHETIC, TEST MNL-TEST-TEST")).put("pages",org.json.JSONArray().put(org.json.JSONArray().apply {
   for(i in 0..30) {
    val d=LocalDate.of(2026,11,1).plusDays(i.toLong())
    fun word(t:String,y:Int) { put(org.json.JSONObject().put("text",t).put("xMin",i*20).put("xMax",i*20+10).put("yMin",y)) }
    word(d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")),0);word("Mon",15);word("OFF",30)
   }
  }))
  val j=if(path==null) synthetic else org.json.JSONObject(java.io.File(path).readText())
  val pages=j.getJSONArray("pages");val texts=j.getJSONArray("texts")
  try {
   val roster=RosterPdf.parseLayout((0 until pages.length()).map { i-> val a=pages.getJSONArray(i);(0 until a.length()).map { k-> val w=a.getJSONObject(k);Word(w.getString("text"),w.getDouble("xMin").toFloat(),w.getDouble("xMax").toFloat(),w.getDouble("yMin").toFloat()) } },(0 until texts.length()).map { texts.getString(it) })
   assertTrue("Private fixture yielded no duties",roster.duties.isNotEmpty())
  } catch (e:Exception) { fail("Private fixture layout parse failed: "+e.javaClass.simpleName) }
 }
}
