package com.denia.jmreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun upgradesFromPreviousGlassBase() {
        assertTrue(isNewerReleaseVersion("v2.30-glass.1", "2.19-glass.4"))
        assertFalse(isNewerReleaseVersion("v2.19-glass.4", "2.30-glass.1"))
    }

    @Test fun ordersGlassBuildsAsIntegers() {
        assertTrue(isNewerReleaseVersion("v2.30-glass.10", "2.30-glass.9"))
        assertFalse(isNewerReleaseVersion("2.30-glass.9", "2.30-glass.10"))
    }

    @Test fun preservesUpstreamDecimalVersioning() {
        assertTrue(isNewerReleaseVersion("2.2", "2.19"))
        assertFalse(isNewerReleaseVersion("2.20", "2.2"))
        assertFalse(isNewerReleaseVersion("2.30", "2.3"))
    }

    @Test fun doesNotPromptForSameOrMalformedVersions() {
        assertFalse(isNewerReleaseVersion("v2.30-glass.1", "2.30-glass.1"))
        assertFalse(isNewerReleaseVersion("v2.30-beta", "2.19-glass.4"))
        assertFalse(isNewerReleaseVersion("2.30-glass.2", "unknown"))
        assertFalse(isNewerReleaseVersion("", "2.30-glass.1"))
    }
}
