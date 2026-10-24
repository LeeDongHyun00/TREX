package com.example.trex_kotlin.weather

import com.example.trex_kotlin.KeyValueStore
import com.example.trex_kotlin.sync.HttpResponse
import com.example.trex_kotlin.sync.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 날씨 저장소·위치 고르기(§108e — weather 브랜치 규칙을 두 플랫폼 공유로 옮긴 것)를 고정한다: 캐시 한 줄의 왕복, 좌표 키,
 * 1시간 안은 받지 않음·강제 새로고침·실패 시 3시간까지 묵은 캐시·그 뒤 실패, 저장소에 남은 캐시, GPS·지역 고르기 규칙.
 * 응답 JSON 해석(org.json — 유닛 테스트에서는 스텁)은 가짜로 바꿔 끼운다. 메모리 캐시는 앱 수명 하나라 테스트마다 다른 좌표를 쓴다.
 */
class WeatherRepositoryTest {

    private class MemoryStore : KeyValueStore {
        val values = HashMap<String, Any?>()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun contains(key: String): Boolean = key in values
        override fun setChangeListener(onChange: (String) -> Unit) {}
        override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
            override fun putString(key: String, value: String?) = apply { if (value == null) values.remove(key) else values[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { if (values == null) this@MemoryStore.values.remove(key) else this@MemoryStore.values[key] = values }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun apply() {}
            override fun commit(): Boolean = true
        }
    }

    private class FakeHttp : HttpTransport {
        var calls = 0
        var failing = false
        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            calls++
            if (failing) throw IOException("네트워크 없음")
            return HttpResponse(200, "{}")
        }
    }

    private val minute = 60_000L

    @Test
    fun cacheLineRoundTripsEveryField() {
        val w = WeatherInfo(18.4, 0.2, 2, 11.3, fetchedAtMillis = 1_700_000_000_000L, isDay = false)
        assertEquals(w, WeatherRepository.decodeCache(WeatherRepository.encodeCache(w)))
        val bare = WeatherInfo(-3.0, 0.0, 71, 4.0, fetchedAtMillis = 5L)
        assertEquals(bare, WeatherRepository.decodeCache(WeatherRepository.encodeCache(bare)))
    }

    @Test
    fun foreignOrBrokenCacheIsIgnored() {
        assertNull("브랜치의 JSON 캐시는 새로 받는다", WeatherRepository.decodeCache("""{"t":1.0,"p":0.0,"c":1,"w":2.0,"ts":5}"""))
        assertNull("예전 판(시간별 포함)도 새로 받는다", WeatherRepository.decodeCache("2|5|18.4|0.2|2|11.3|17.1|0|21.0|12.5|15:18.0:2"))
        assertNull(WeatherRepository.decodeCache("3|x|1|0|1|2|1"))
        assertNull(WeatherRepository.decodeCache(""))
    }

    @Test
    fun cacheKeyKeepsTwoDecimals() {
        assertEquals("37.57_126.98", WeatherRepository.cacheKey(37.5665, 126.9780))
        assertEquals("35.18_129.08", WeatherRepository.cacheKey(35.1796, 129.0756))
        assertEquals("-33.87_151.21", WeatherRepository.cacheKey(-33.8688, 151.2093))
        assertEquals("0.05_-0.05", WeatherRepository.cacheKey(0.049, -0.049))
    }

    @Test
    fun freshCacheSkipsNetworkForceRefetchesStaleCoversFailureUpToThreeHours() = runBlocking {
        var now = 1_000_000_000L
        val http = FakeHttp()
        val repo = WeatherRepository(http, MemoryStore(), clock = { now }, parse = { _, at -> WeatherInfo(20.0, 0.0, 0, 3.0, fetchedAtMillis = at) }, retryDelayMs = 0)

        assertTrue(repo.fetchCurrentWeather(10.0, 10.0).isSuccess)
        assertEquals(1, http.calls)
        now += 30 * minute
        assertEquals("1시간 안은 받지 않는다", now - 30 * minute, repo.fetchCurrentWeather(10.0, 10.0).getOrThrow().fetchedAtMillis)
        assertEquals(1, http.calls)
        repo.fetchCurrentWeather(10.0, 10.0, forceRefresh = true)
        assertEquals("새로고침은 받는다", 2, http.calls)

        val lastOk = now
        http.failing = true
        now += 2 * 60 * minute
        val stale = repo.fetchCurrentWeather(10.0, 10.0)
        assertEquals("두 번 시도하고", 4, http.calls)
        assertEquals("3시간 안의 묵은 캐시로 물러선다", lastOk, stale.getOrThrow().fetchedAtMillis)
        now += 61 * minute
        assertTrue("3시간이 지나면 실패", repo.fetchCurrentWeather(10.0, 10.0).isFailure)
    }

    @Test
    fun storedCacheSurvivesANewRepositoryAndPeekShowsItWithoutNetwork() = runBlocking {
        val now = 2_000_000_000L
        val store = MemoryStore()
        val saved = WeatherInfo(9.0, 0.0, 3, 6.0, fetchedAtMillis = now - 10 * minute)
        store.edit().putString(WeatherRepository.cacheKey(20.0, 20.0), WeatherRepository.encodeCache(saved)).apply()
        val http = FakeHttp()
        val repo = WeatherRepository(http, store, clock = { now }, parse = { _, _ -> error("받으면 안 된다") }, retryDelayMs = 0)
        assertEquals(saved, repo.peekCachedWeather(20.0, 20.0))
        assertEquals(saved, repo.fetchCurrentWeather(20.0, 20.0).getOrThrow())
        assertEquals(0, http.calls)
    }

    private class FakeLocator(private val permitted: Boolean, private val coordinates: Pair<Double, Double>?) : WeatherLocator {
        override fun hasPermission() = permitted
        override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(permitted)
        override suspend fun currentCoordinates() = coordinates
    }

    @Test
    fun placeFollowsGpsModePermissionAndSavedRegion() = runBlocking {
        val prefs = WeatherLocationPrefs(MemoryStore())
        assertEquals(WeatherPlace(37.5665, 126.9780, "서울"), resolveWeatherPlace(prefs, FakeLocator(true, 1.0 to 2.0)))
        prefs.saveSelectedRegion(KoreanRegion.BUSAN)
        assertEquals("부산", resolveWeatherPlace(prefs, FakeLocator(true, 1.0 to 2.0)).label)
        prefs.enableGpsMode()
        assertEquals(WeatherPlace(1.0, 2.0, "현재 위치"), resolveWeatherPlace(prefs, FakeLocator(true, 1.0 to 2.0)))
        assertEquals(WeatherPlace(35.1796, 129.0756, "부산 (위치 확인 불가)"), resolveWeatherPlace(prefs, FakeLocator(true, null)))
        assertEquals("권한이 없으면 저장 지역", "부산", resolveWeatherPlace(prefs, FakeLocator(false, 1.0 to 2.0)).label)
        prefs.saveSelectedRegion(KoreanRegion.JEJU)
        assertEquals("지역을 고르면 GPS 모드가 꺼진다", "제주", resolveWeatherPlace(prefs, FakeLocator(true, 1.0 to 2.0)).label)
    }
}
