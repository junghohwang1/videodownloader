package com.example.videodownloader.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(AppUpdater.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(AppUpdater.compareVersions("1.1", "1.0.9") > 0)
        assertTrue(AppUpdater.compareVersions("1.0.1", "1.0.2") < 0)
        assertEquals(0, AppUpdater.compareVersions("1.0", "1.0.0"))
    }
}
