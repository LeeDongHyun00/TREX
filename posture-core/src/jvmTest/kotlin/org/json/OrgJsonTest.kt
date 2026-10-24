package org.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §108: iPhone 용 org.json 공통 판이 Android org.json(libcore)과 같은 문자열·같은 강제 변환을 내는가. 기댓값은 Android 동작이다. */
class OrgJsonTest {

    @Test
    fun writesLikeAndroid() {
        val o = JSONObject()
            .put("int", 1)
            .put("wholeDouble", 65.0)
            .put("double", 1.5)
            .put("long", 1_761_100_000_123L)
            .put("bool", true)
            .put("text", "x/y\"z\\\n\t기본")
            .put("control", "\u0001")
            .put("negZero", -0.0)
            .put("arr", JSONArray().put(1).put("s").put(2.0).put(JSONObject.NULL))
            .put("obj", JSONObject().put("k", "v"))
        assertEquals(
            """{"int":1,"wholeDouble":65,"double":1.5,"long":1761100000123,"bool":true,"text":"x\/y\"z\\\n\t기본",""" +
                """"control":"\u0001","negZero":-0,"arr":[1,"s",2,null],"obj":{"k":"v"}}""",
            o.toString(),
        )
    }

    @Test
    fun keepsKeyOrderAndNumberTypesFromText() {
        val o = JSONObject("""{"z":2147483647,"a":2147483648,"m":1.0,"e":1e3}""")
        assertEquals(listOf("z", "a", "m", "e"), o.keys().asSequence().toList())
        assertTrue(o.opt("z") is Int)
        assertTrue(o.opt("a") is Long)
        assertTrue(o.opt("m") is Double)
        assertEquals("""{"z":2147483647,"a":2147483648,"m":1,"e":1000}""", o.toString())
    }

    @Test
    fun optAndGetCoerceLikeAndroid() {
        val o = JSONObject("""{"n":null,"i":5,"d":5.5,"s":"12","word":"abc","t":"TRUE","f":3.9}""")
        assertEquals("null", o.optString("n"))   // TrexStore 주석: JSON null 은 "null" 로 읽힌다
        assertEquals("null", o.getString("n"))
        assertEquals("5", o.optString("i"))
        assertEquals("5.5", o.optString("d"))
        assertEquals("", o.optString("missing"))
        assertEquals("fb", o.optString("missing", "fb"))
        assertEquals(12, o.getInt("s"))
        assertEquals(7, o.optInt("word", 7))
        assertEquals(5.0, o.getDouble("i"), 0.0)
        assertTrue(o.optBoolean("t"))
        assertFalse(o.optBoolean("missing"))
        assertEquals(3L, o.getLong("f"))
        assertEquals(3, o.getInt("f"))
        assertTrue(o.optDouble("missing").isNaN())
        assertEquals(0L, o.optLong("missing"))
    }

    @Test
    fun nullAndPresence() {
        val o = JSONObject("""{"n":null,"x":1}""")
        assertTrue(o.has("n"))
        assertTrue(o.isNull("n"))
        assertTrue(o.isNull("missing"))
        assertFalse(o.isNull("x"))
        assertNull(o.optJSONObject("x"))
        o.put("x", null as Any?)   // Android: null 을 넣으면 지운다
        assertFalse(o.has("x"))
        o.remove("n")
        assertEquals(0, o.length())
    }

    @Test
    fun errorsLikeAndroid() {
        val o = JSONObject("""{"s":"x","n":null}""")
        assertThrows { o.get("missing") }
        assertThrows { o.getInt("s") }
        assertThrows { o.getJSONObject("s") }
        assertThrows { JSONObject(null as String?) }
        assertThrows { JSONObject("[1]") }
        assertThrows { JSONArray("{}") }
        assertThrows { JSONObject().put("nan", Double.NaN) }
        val a = JSONArray("[null,1]")
        assertTrue(a.get(0) === JSONObject.NULL)   // Android: JSON null 은 get 에서 NULL 로 돌아온다
        assertNull(a.opt(5))
        assertThrows { a.get(5) }
    }

    @Test
    fun roundTripsTrexStorePlanAndBom() {
        val plan = """[{"id":"a1","name":"기본 스쿼트","reps":"10회 × 3세트","duration":"10분","posture":true,"category":"하체",""" +
            """"done":false,"alt":{"name":"런지","reps":"10회"},"secondsPerRep":3,"targetKind":"repetitions","targetAmount":12}]"""
        val arr = JSONArray("﻿" + plan)
        assertEquals(plan, arr.toString())
        val w = arr.getJSONObject(0)
        assertEquals("기본 스쿼트", w.getString("name"))
        assertTrue(w.getBoolean("posture"))
        assertEquals("런지", w.optJSONObject("alt")!!.getString("name"))
        assertEquals(12, w.optInt("targetAmount", 0))
        assertEquals(45, w.optInt("restSeconds", 45))
    }

    @Test
    fun quoteAndCollectionLikeAndroid() {
        assertEquals("\"a\\/b\\\"c\"", JSONObject.quote("a/b\"c"))
        assertEquals("\"\"", JSONObject.quote(null))
        assertEquals("[\"CHEST\",\"QUADS\"]", JSONArray(listOf("CHEST", "QUADS")).toString())
        assertEquals("[]", JSONArray(emptySet<String>()).toString())
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: JSONException) {
            return
        }
        throw AssertionError("JSONException 이 나야 한다")
    }
}
