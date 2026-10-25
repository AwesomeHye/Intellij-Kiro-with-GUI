package com.kiro.intellij.toolwindow

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * KiroMessages multilingual system tests.
 * Tests reference KiroMessages.koMap and KiroMessages.enMap directly
 * instead of maintaining duplicate copies, so key mismatches are caught automatically.
 */
class KiroMessagesTest {

    private val koMessages get() = KiroMessages.koMap
    private val enMessages get() = KiroMessages.enMap

    @Test
    fun `ko and en should have exactly same keys`() {
        val koKeys = koMessages.keys.sorted()
        val enKeys = enMessages.keys.sorted()

        val missingInEn = koKeys - enKeys.toSet()
        val missingInKo = enKeys - koKeys.toSet()

        assertTrue(missingInEn.isEmpty(), "Keys missing in EN: $missingInEn")
        assertTrue(missingInKo.isEmpty(), "Keys missing in KO: $missingInKo")
        assertEquals(koKeys, enKeys, "Key sets should be identical")
    }

    @Test
    fun `all ko messages should not be blank`() {
        koMessages.forEach { (key, value) ->
            assertTrue(value.isNotBlank(), "KO message blank for key: $key")
        }
    }

    @Test
    fun `all en messages should not be blank`() {
        enMessages.forEach { (key, value) ->
            assertTrue(value.isNotBlank(), "EN message blank for key: $key")
        }
    }

    @Test
    fun `ko messages should contain Korean characters where expected`() {
        val koreanPattern = Regex("[가-힣]")
        // Keys that must contain Korean characters (excluding proper nouns)
        val koOnlyKeys = listOf(
            "tab.chat", "tab.manage", "nav.auth", "nav.settings",
            "auth.title", "auth.currentStatus", "auth.status", "auth.checking",
            "auth.loggedIn", "auth.loginRequired", "auth.error", "auth.login", "auth.logout",
            "auth.welcome", "auth.welcomeDesc", "auth.features",
            "settings.title", "settings.cliPath", "settings.defaultModel", "settings.language",
            "settings.save", "settings.reset", "settings.saved", "settings.saveComplete",
            "settings.configDir", "settings.configDirDesc", "settings.configDirHint",
            "settings.configDirCurrent", "settings.configDirDefault",
            "settings.theme", "settings.themeDesc",
            "mcp.title", "mcp.addServer", "mcp.add", "mcp.edit", "mcp.workspace", "mcp.global", "mcp.noServers",
            "mcp.command", "mcp.commandPrefix", "mcp.active", "mcp.inactive", "mcp.activate", "mcp.deactivate",
            "mcp.selectFile", "mcp.selectTitle", "mcp.openFailed",
            "agent.create", "agent.currentActive", "agent.noAgents", "agent.active", "agent.switch",
            "chat.placeholder", "chat.openFile", "chat.systemLog",
            "common.error", "common.refresh", "common.edit", "common.delete", "common.remove"
        )

        koOnlyKeys.forEach { key ->
            val value = koMessages[key]
            assertNotNull(value, "Key not found: $key")
            assertTrue(koreanPattern.containsMatchIn(value!!), "KO[$key] = '$value' should contain Korean")
        }
    }

    @Test
    fun `en messages should not contain Korean characters`() {
        val koreanPattern = Regex("[가-힣]")
        enMessages.forEach { (key, value) ->
            assertFalse(koreanPattern.containsMatchIn(value), "EN[$key] = '$value' should not contain Korean")
        }
    }

    @Test
    fun `ko and en messages should be different for translatable keys`() {
        // Keys that must differ between languages (excluding proper nouns)
        val translatableKeys = listOf(
            "tab.chat", "tab.manage", "nav.auth", "nav.settings",
            "auth.title", "auth.currentStatus", "auth.login", "auth.logout", "auth.welcome",
            "settings.title", "settings.save", "settings.reset",
            "settings.configDir", "settings.configDirHint",
            "mcp.title", "mcp.addServer", "mcp.add", "mcp.edit", "mcp.workspace", "mcp.global",
            "agent.create", "agent.noAgents",
            "chat.placeholder", "chat.openFile", "chat.systemLog",
            "common.error", "common.refresh", "common.edit", "common.delete", "common.remove"
        )

        translatableKeys.forEach { key ->
            val koValue = koMessages[key]
            val enValue = enMessages[key]
            assertNotEquals(koValue, enValue, "KO and EN should differ for key: $key (both are '$koValue')")
        }
    }

    @Test
    fun `navigation keys should all exist`() {
        val navKeys = listOf("nav.auth", "nav.settings", "nav.mcp", "nav.skills", "nav.agent")
        navKeys.forEach { key ->
            assertTrue(koMessages.containsKey(key), "Missing KO nav key: $key")
            assertTrue(enMessages.containsKey(key), "Missing EN nav key: $key")
        }
    }

    @Test
    fun `settings panel keys should all exist`() {
        val settingsKeys = listOf(
            "settings.title", "settings.cliPath", "settings.cliPathDesc",
            "settings.configDir", "settings.configDirDesc", "settings.configDirHint",
            "settings.configDirCurrent", "settings.configDirDefault",
            "settings.defaultModel", "settings.defaultModelDesc",
            "settings.language", "settings.languageDesc",
            "settings.theme", "settings.themeDesc",
            "settings.trustAllTools", "settings.trustAllToolsDesc",
            "settings.save", "settings.reset", "settings.openConfig",
            "settings.saved", "settings.saveComplete",
            "settings.resetConfirm", "settings.resetTitle",
            "settings.restartConfirm", "settings.restartTitle",
            "settings.configNotFound", "settings.cliNotFound", "settings.info"
        )
        settingsKeys.forEach { key ->
            assertTrue(koMessages.containsKey(key), "Missing KO settings key: $key")
            assertTrue(enMessages.containsKey(key), "Missing EN settings key: $key")
        }
    }

    @Test
    fun `skills panel keys should all exist`() {
        val skillsKeys = listOf(
            "skills.title", "skills.search", "skills.hint", "skills.noResults",
            "skills.provider", "skills.refreshHint", "skills.info",
            "skills.desc.getCurrentSelection", "skills.desc.getOpenEditors",
            "skills.desc.getWorkspaceFolders", "skills.desc.getDiagnostics",
            "skills.desc.openFile", "skills.desc.openDiff"
        )
        skillsKeys.forEach { key ->
            assertTrue(koMessages.containsKey(key), "Missing KO skills key: $key")
            assertTrue(enMessages.containsKey(key), "Missing EN skills key: $key")
        }
    }

    @Test
    fun `mcp panel keys should all exist`() {
        val mcpKeys = listOf(
            "mcp.title", "mcp.addServer", "mcp.add", "mcp.edit", "mcp.editJson",
            "mcp.workspace", "mcp.global", "mcp.parseError", "mcp.noServers",
            "mcp.command", "mcp.commandPrefix", "mcp.active", "mcp.inactive", "mcp.activate", "mcp.deactivate",
            "mcp.removeConfirm", "mcp.removeTitle",
            "mcp.addFailed", "mcp.removeFailed", "mcp.toggleFailed", "mcp.notFound",
            "mcp.name", "mcp.commandLabel", "mcp.scope", "mcp.addTitle",
            "mcp.selectFile", "mcp.selectTitle", "mcp.openFailed"
        )
        mcpKeys.forEach { key ->
            assertTrue(koMessages.containsKey(key), "Missing KO mcp key: $key")
            assertTrue(enMessages.containsKey(key), "Missing EN mcp key: $key")
        }
    }

    @Test
    fun `chat panel keys should all exist`() {
        val chatKeys = listOf("chat.placeholder", "chat.openFile", "chat.systemLog")
        chatKeys.forEach { key ->
            assertTrue(koMessages.containsKey(key), "Missing KO chat key: $key")
            assertTrue(enMessages.containsKey(key), "Missing EN chat key: $key")
        }
    }

    @Test
    fun `total message count should match between ko and en`() {
        assertEquals(
            koMessages.size, enMessages.size,
            "KO has ${koMessages.size} messages, EN has ${enMessages.size} messages"
        )
    }

    @Test
    fun `message count should be at least 80`() {
        assertTrue(koMessages.size >= 80, "Expected at least 80 messages, got ${koMessages.size}")
        assertTrue(enMessages.size >= 80, "Expected at least 80 messages, got ${enMessages.size}")
    }
}
