package com.kiro.intellij.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KiroCliValidatorTest {

    @Test
    fun `resolveCliPath should find existing executable`() {
        // /bin/sh is universally available
        val result = KiroCliValidator.resolveCliPath("/bin/sh")
        assertNotNull(result)
        assertEquals("/bin/sh", result)
    }

    @Test
    fun `resolveCliPath should return null for nonexistent binary`() {
        val result = KiroCliValidator.resolveCliPath("/nonexistent/path/fake-kiro-cli-xyz")
        assertNull(result)
    }

    @Test
    fun `resolveCliPath should find command via which`() {
        // 'ls' is available on all unix systems
        val result = KiroCliValidator.resolveCliPath("ls")
        assertNotNull(result)
        assertTrue(result!!.endsWith("/ls"))
    }

    @Test
    fun `getCliVersion should return null for nonexistent binary`() {
        val result = KiroCliValidator.getCliVersion("/nonexistent/path/fake-binary")
        assertNull(result)
    }

    @Test
    fun `checkAuthentication should return false for nonexistent binary`() {
        val result = KiroCliValidator.checkAuthentication("/nonexistent/path/fake-binary")
        assertFalse(result)
    }

    @Test
    fun `authentication command should use whoami supported by current CLI`() {
        assertEquals(listOf("/path/to/kiro-cli", "whoami"), KiroCliValidator.authenticationCommand("/path/to/kiro-cli"))
    }

    @Test
    fun `successful non-empty whoami output should be authenticated`() {
        assertTrue(KiroCliValidator.isAuthenticatedOutput("User: test@example.com", 0))
    }

    @Test
    fun `failed or error whoami output should not be authenticated`() {
        assertFalse(KiroCliValidator.isAuthenticatedOutput("", 0))
        assertFalse(KiroCliValidator.isAuthenticatedOutput("error: not logged in", 0))
        assertFalse(KiroCliValidator.isAuthenticatedOutput("User: test@example.com", 1))
    }

    @Test
    fun `ValidationResult isReady should be true when cli found and no error`() {
        val result = KiroCliValidator.ValidationResult(
            cliFound = true,
            cliPath = "/usr/bin/kiro-cli",
            version = "1.0.0",
            authenticated = true,
            errorMessage = null
        )
        assertTrue(result.isReady)
    }

    @Test
    fun `ValidationResult isReady should be false when cli not found`() {
        val result = KiroCliValidator.ValidationResult(
            cliFound = false,
            cliPath = null,
            version = null,
            authenticated = false,
            errorMessage = "CLI not found"
        )
        assertFalse(result.isReady)
    }

    @Test
    fun `ValidationResult isReady should be false when error exists`() {
        val result = KiroCliValidator.ValidationResult(
            cliFound = true,
            cliPath = "/usr/bin/kiro-cli",
            version = "1.0.0",
            authenticated = false,
            errorMessage = "Version incompatible"
        )
        assertFalse(result.isReady)
    }

    @Test
    fun `cache invalidation should work`() {
        KiroCliValidator.invalidateCache()
        // After invalidation, the next validate() call should perform fresh validation
        // We can't easily test the caching behavior without mocking, but we verify no exception
        assertDoesNotThrow { KiroCliValidator.invalidateCache() }
    }
}
