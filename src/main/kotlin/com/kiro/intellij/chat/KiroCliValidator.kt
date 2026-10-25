package com.kiro.intellij.chat

import com.intellij.openapi.diagnostic.Logger
import com.kiro.intellij.settings.KiroCliResolver
import com.kiro.intellij.settings.KiroSettings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * kiro-cli 설치 상태, 버전 호환성, 인증 상태를 검증한다.
 * 결과는 캐싱하여 반복 호출 시 프로세스를 재실행하지 않는다.
 */
object KiroCliValidator {

    private val log = Logger.getInstance(KiroCliValidator::class.java)

    private const val CACHE_TTL_MS = 60_000L
    private var cachedResult: ValidationResult? = null
    private var cachedAt: Long = 0

    data class ValidationResult(
        val cliFound: Boolean,
        val cliPath: String?,
        val version: String?,
        val authenticated: Boolean,
        val errorMessage: String?
    ) {
        val isReady: Boolean get() = cliFound && errorMessage == null
    }

    fun validate(forceRefresh: Boolean = false): ValidationResult {
        val now = System.currentTimeMillis()
        val cached = cachedResult
        if (!forceRefresh && cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached
        }

        val result = runValidation()
        cachedResult = result
        cachedAt = now
        return result
    }

    fun invalidateCache() {
        cachedResult = null
        cachedAt = 0
    }

    private fun runValidation(): ValidationResult {
        val cliPath = KiroCliResolver.resolve()
        
        if (!File(cliPath).let { it.exists() && it.canExecute() } && cliPath == "kiro-cli") {
            return ValidationResult(
                cliFound = false,
                cliPath = null,
                version = null,
                authenticated = false,
                errorMessage = "kiro-cli not found. Check installation path."
            )
        }

        val version = getCliVersion(cliPath)

        val authenticated = checkAuthentication(cliPath)

        return ValidationResult(
            cliFound = true,
            cliPath = cliPath,
            version = version,
            authenticated = authenticated,
            errorMessage = null
        )
    }

    fun resolveCliPath(command: String): String? {
        if (File(command).let { it.exists() && it.canExecute() }) {
            return command
        }

        return try {
            val process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (completed && process.exitValue() == 0) {
                process.inputStream.bufferedReader().readLine()?.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            log.debug("which $command failed", e)
            tryCommonPaths(command)
        }
    }

    private fun tryCommonPaths(command: String): String? {
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "/usr/local/bin/$command",
            "/opt/homebrew/bin/$command",
            "$home/.local/bin/$command",
            "$home/.kiro/bin/$command",
            "$home/.npm-global/bin/$command"
        )
        return candidates.firstOrNull { File(it).let { f -> f.exists() && f.canExecute() } }
    }

    fun getCliVersion(cliPath: String): String? {
        return try {
            val process = ProcessBuilder(cliPath, "--version")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() == 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                VERSION_REGEX.find(output)?.value ?: output.take(50)
            } else {
                null
            }
        } catch (e: Exception) {
            log.debug("Version check failed for $cliPath", e)
            null
        }
    }

    fun checkAuthentication(cliPath: String): Boolean {
        return try {
            val process = ProcessBuilder(authenticationCommand(cliPath))
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return false
            }
            val output = process.inputStream.bufferedReader().readText()
            isAuthenticatedOutput(output, process.exitValue())
        } catch (e: Exception) {
            log.debug("Auth check failed for $cliPath", e)
            false
        }
    }

    internal fun authenticationCommand(cliPath: String): List<String> = listOf(cliPath, "whoami")

    internal fun isAuthenticatedOutput(output: String, exitCode: Int): Boolean =
        exitCode == 0 && output.isNotBlank() &&
            !output.contains("not logged in", ignoreCase = true) &&
            !output.contains("error", ignoreCase = true)

    private val VERSION_REGEX = Regex("""\d+\.\d+(\.\d+)?(-[\w.]+)?""")
}
