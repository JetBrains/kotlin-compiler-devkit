package org.jetbrains.kotlin.test.helper

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestDataPluginSettingsTest {
    @Test
    fun testFederationGutterMarkersAreEnabledByDefaultAndForLegacyState() {
        val legacyState = TestDataPathsConfiguration()
        val configuration = TestDataPathsConfiguration()

        configuration.loadState(legacyState)

        assertTrue(configuration.testFederationGutterMarkersEnabled)
    }

    @Test
    fun testFederationGutterMarkerSettingIsSavedAndLoaded() {
        val savedState = TestDataPathsConfiguration().apply {
            testFederationGutterMarkersEnabled = false
        }
        val loadedConfiguration = TestDataPathsConfiguration()

        loadedConfiguration.loadState(savedState.state)

        assertFalse(loadedConfiguration.testFederationGutterMarkersEnabled)
    }
}
