package com.reproductor.audiolibros

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real typed preference reads in a separate file, never the playback service's bookmarks. */
@RunWith(AndroidJUnit4::class)
class PositionStoreInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = target.getSharedPreferences("position-store-test", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    @Before fun prepare() { assertTrue(prefs.edit().clear().commit()) }
    @After fun cleanup() { assertTrue(prefs.edit().clear().commit()) }

    @Test fun malformedBookmarksDoNotPreventSelectingAFileOrDamageOtherPositions() {
        assertTrue(prefs.edit().putString("pos_broken", "invalid")
            .putLong("pos_valid", 91_234_567L).commit())
        val store = PositionStore(context)
        assertEquals(0L, store.getPosition("broken"))
        assertFalse(prefs.contains("pos_broken"))
        assertEquals(91_234_567L, store.getPosition("valid"))
        store.savePosition("broken", 5_000L, force = true)
        assertEquals(5_000L, PositionStore(context).getPosition("broken"))
        assertEquals(91_234_567L, PositionStore(context).getPosition("valid"))
    }

    @Test fun legacyLongPositionsBeyondTwentyFourHoursSurviveCacheEviction() {
        val position = 25L * 60 * 60 * 1_000 + 123
        assertTrue(prefs.edit().putLong("pos_long-book", position).commit())
        val store = PositionStore(context)
        assertEquals(position, store.getPosition("long-book"))
        repeat(600) { store.getPosition("other-$it") }
        assertEquals(position, store.getPosition("long-book"))
        assertEquals(position, PositionStore(context).getPosition("long-book"))
    }
}
