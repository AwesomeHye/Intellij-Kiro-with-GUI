package com.kiro.intellij.settings

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KiroSettingsTest {

    @Test
    fun `default state should have correct values`() {
        val state = KiroSettings.State()

        assertEquals("kiro-cli", state.kiroCommand)
        assertEquals("Auto", state.defaultModel)
        assertEquals("en", state.language)
        assertEquals("", state.kiroConfigDir)
        assertEquals("auto", state.theme)
        assertFalse(state.trustAllTools)
    }

    @Test
    fun `state should be mutable`() {
        val state = KiroSettings.State()
        
        state.kiroCommand = "/usr/local/bin/kiro-cli"
        state.defaultModel = "claude-opus-4.5"
        state.language = "en"
        
        assertEquals("/usr/local/bin/kiro-cli", state.kiroCommand)
        assertEquals("claude-opus-4.5", state.defaultModel)
        assertEquals("en", state.language)
    }

    @Test
    fun `state copy should work correctly`() {
        val original = KiroSettings.State(
            kiroCommand = "/custom/path",
            defaultModel = "claude-sonnet-4"
        )
        
        val copy = original.copy()
        
        assertEquals(original.kiroCommand, copy.kiroCommand)
        assertEquals(original.defaultModel, copy.defaultModel)
        assertEquals(original.language, copy.language)
    }
}
