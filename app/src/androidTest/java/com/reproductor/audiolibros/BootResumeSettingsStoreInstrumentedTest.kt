package com.reproductor.audiolibros

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootResumeSettingsStoreInstrumentedTest {
    private lateinit var directory: File
    private lateinit var context: Context

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "boot-resume-settings-test")
        directory.deleteRecursively()
        assertTrue(directory.mkdirs())
        context = object : ContextWrapper(target) {
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun missingDefaultsOffAndBothValuesRoundTrip() {
        assertFalse(BootResumeSettingsStore(context).readEnabled())
        assertTrue(BootResumeSettingsStore(context).write(true))
        assertTrue(BootResumeSettingsStore(context).readEnabled())
        assertTrue(BootResumeSettingsStore(context).write(false))
        assertFalse(BootResumeSettingsStore(context).readEnabled())
    }

    @Test fun corruptAndOversizedFilesFailClosed() {
        val file = File(directory, "boot_resume.json")
        for (value in listOf("{", "{\"version\":1,\"enabled\":true} trailing",
            "{\"version\":1,\"enabled\":\"true\"}",
            "{\"version\":2,\"enabled\":true}", " ".repeat(257))) {
            file.writeText(value)
            assertFalse(value, BootResumeSettingsStore(context).readEnabled())
        }
    }

    @Test @SdkSuppress(minSdkVersion = 30)
    fun failedStagingWritePreservesPreviouslyCommittedSetting() {
        val store = BootResumeSettingsStore(context)
        assertTrue(store.write(true))
        val blocked = File(directory, "boot_resume.json.new")
        assertTrue(blocked.mkdir())
        try { assertFalse(store.write(false)) } finally { blocked.deleteRecursively() }
        assertTrue(BootResumeSettingsStore(context).readEnabled())
        assertTrue(store.write(false))
        assertFalse(store.readEnabled())
    }
}
