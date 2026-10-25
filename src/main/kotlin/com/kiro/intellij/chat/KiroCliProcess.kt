package com.kiro.intellij.chat

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kiro.intellij.settings.KiroCliResolver
import com.kiro.intellij.settings.KiroSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * kiro-cli를 매 메시지마다 --no-interactive로 실행.
 * 저장된 CLI 세션 ID가 있으면 --resume-id로 정확한 대화를 이어간다.
 * 출력을 실시간 스트리밍하여 콜백으로 전달.
 */
class KiroCliProcess(
    private val project: Project,
    initialSessionId: String? = null,
    private val onSessionIdChanged: (String?) -> Unit = {}
) {

    private val log = Logger.getInstance(KiroCliProcess::class.java)
    private var cliSessionId: String? = initialSessionId
    private var isFirstMessage = initialSessionId == null
    private val isBusy = AtomicBoolean(false)
    private val consecutiveErrors = AtomicInteger(0)
    private var currentProcess: Process? = null
    // 실행 세대. stop()이나 새 실행 시작 시 증가하며, 낡은 실행의 늦은 콜백(done/chunk/error)이
    // 다음 메시지의 이벤트 스트림에 끼어드는 것을 막는다.
    private val runGeneration = AtomicInteger(0)
    var model: String? = null

    fun sendMessage(message: String, onChunk: (String) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        if (!isBusy.compareAndSet(false, true)) {
            onError("Previous request is still in progress.")
            return
        }

        val trimmedMsg = message.trim()
        if (trimmedMsg.startsWith("/")) {
            val handled = handleSlashCommand(trimmedMsg, onChunk, onDone, onError)
            if (handled) {
                isBusy.set(false)
                return
            }
        }

        val gen = runGeneration.incrementAndGet()
        // 이 실행이 최신 세대일 때만 콜백 전달
        val guardedChunk: (String) -> Unit = { if (runGeneration.get() == gen) onChunk(it) }
        val guardedError: (String) -> Unit = { if (runGeneration.get() == gen) onError(it) }

        Thread({
            try {
                val validation = KiroCliValidator.validate()
                if (!validation.cliFound) {
                    guardedError(validation.errorMessage ?: "kiro-cli not found.")
                    return@Thread
                }

                val settings = KiroSettings.getInstance().state
                val cliPath = validation.cliPath ?: settings.kiroCommand
                val workingDir = project.basePath ?: System.getProperty("user.home")
                val sessionsBefore = if (cliSessionId == null) {
                    listCliSessions(cliPath, workingDir).map { it.id }.toSet()
                } else {
                    emptySet()
                }
                val effectiveModel = model ?: settings.defaultModel
                val command = buildChatCommand(
                    cliPath = cliPath,
                    message = message,
                    resumeId = cliSessionId,
                    isFirstMessage = isFirstMessage,
                    model = effectiveModel,
                    trustAllTools = settings.trustAllTools
                )

                val pb = ProcessBuilder(command)
                    .directory(File(workingDir))
                    .redirectErrorStream(true)
                KiroCliResolver.configureProcessBuilder(pb)
                // 터미널 너비를 넓게 설정하여 코드블럭 줄바꿈 방지
                pb.environment()["COLUMNS"] = "500"
                pb.environment()["TERM"] = "dumb"
                val process = pb.start()
                
                currentProcess = process

                val inputStream = process.inputStream
                val emitter = OutputEmitter(guardedChunk)

                val buf = ByteArray(1024)
                var bytesRead: Int
                while (inputStream.read(buf).also { bytesRead = it } != -1) {
                    emitter.feed(String(buf, 0, bytesRead, Charsets.UTF_8))
                }
                emitter.finish()
                val hasOutput = emitter.hasOutput

                val completed = process.waitFor(300, TimeUnit.SECONDS)

                if (!completed) {
                    process.destroyForcibly()
                    guardedError("kiro-cli response timed out (5 min). Process terminated.")
                    consecutiveErrors.incrementAndGet()
                } else {
                    val exitCode = process.exitValue()

                    if (exitCode == 0) {
                        isFirstMessage = false
                        if (cliSessionId == null) {
                            val discovered = discoverCreatedSession(cliPath, workingDir, sessionsBefore)
                            if (discovered != null) {
                                cliSessionId = discovered
                                onSessionIdChanged(discovered)
                            }
                        }
                    }

                    if (exitCode != 0 && exitCode != 130 && exitCode != 143) {
                        val errorMsg = classifyExitCode(exitCode, hasOutput)
                        guardedError(errorMsg)
                        consecutiveErrors.incrementAndGet()
                    } else {
                        consecutiveErrors.set(0)
                    }
                }
            } catch (e: Exception) {
                log.warn("kiro-cli execution failed", e)
                val userMessage = classifyException(e)
                guardedError(userMessage)
                consecutiveErrors.incrementAndGet()
                KiroCliValidator.invalidateCache()
            } finally {
                currentProcess = null
                isBusy.set(false)
                // 중지되었거나 새 실행으로 대체된 경우 늦은 done을 보내지 않는다
                if (runGeneration.get() == gen) onDone()
            }
        }, "kiro-cli-send").apply { isDaemon = true }.start()
    }

    fun stop() {
        // 진행 중이던 실행의 이후 콜백을 무효화 (읽기 스레드의 finally가 늦게 실행되어도 done이 새지 않음)
        runGeneration.incrementAndGet()
        currentProcess?.let { process ->
            try {
                process.destroy()
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                log.warn("Failed to stop kiro-cli process", e)
            }
        }
        currentProcess = null
        isBusy.set(false)
    }

    private fun handleSlashCommand(cmd: String, onChunk: (String) -> Unit, onDone: () -> Unit, onError: (String) -> Unit): Boolean {
        val parts = cmd.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val args = if (parts.size > 1) parts[1] else ""

        when (command) {
            "/model" -> {
                if (args.isBlank()) {
                    val currentModel = model ?: KiroSettings.getInstance().state.defaultModel
                    onChunk("Current model: $currentModel\n")
                    onChunk("Use the model selector button at the bottom of the input to change models.\n")
                    onChunk("Or specify directly with /model <model-name>.\n")
                    onChunk("Example: /model claude-sonnet-4\n")
                    onDone()
                    return true
                } else {
                    model = args.trim()
                    onChunk("Model changed to '${args.trim()}'.\n")
                    onDone()
                    return true
                }
            }
            "/clear" -> {
                resetSession()
                onChunk("Conversation history cleared.\n")
                onDone()
                return true
            }
            "/help" -> {
                onChunk("Available slash commands:\n")
                onChunk("/model [model-name] - View or change current model\n")
                onChunk("/clear - Clear conversation history\n")
                onChunk("/context - Manage context files\n")
                onChunk("/tools - View tools and permissions\n")
                onChunk("/usage - Usage information\n")
                onChunk("/mcp - List MCP servers\n")
                onChunk("/compact - Summarize conversation\n")
                onChunk("/help - Show help\n")
                onDone()
                return true
            }
            "/status" -> {
                val validation = KiroCliValidator.validate(forceRefresh = true)
                onChunk("=== Kiro CLI Status ===\n")
                onChunk("CLI found: ${if (validation.cliFound) "✓" else "✗"}\n")
                if (validation.cliPath != null) onChunk("Path: ${validation.cliPath}\n")
                if (validation.version != null) onChunk("Version: ${validation.version}\n")
                onChunk("Auth: ${if (validation.authenticated) "✓ Logged in" else "✗ Not authenticated"}\n")
                onChunk("Consecutive errors: ${consecutiveErrors.get()}\n")
                if (validation.errorMessage != null) onChunk("Error: ${validation.errorMessage}\n")
                onDone()
                return true
            }
        }
        return false
    }

    fun resetSession() {
        cliSessionId = null
        isFirstMessage = true
        consecutiveErrors.set(0)
        onSessionIdChanged(null)
    }

    private fun discoverCreatedSession(cliPath: String, workingDir: String, sessionsBefore: Set<String>): String? {
        return listCliSessions(cliPath, workingDir)
            .asSequence()
            .filter { it.source == "v2" && it.id !in sessionsBefore }
            .maxByOrNull { it.updatedAt }
            ?.id
    }

    private fun listCliSessions(cliPath: String, workingDir: String): List<CliSessionInfo> {
        return try {
            val processBuilder = ProcessBuilder(
                cliPath,
                "chat",
                "--list-sessions",
                "--format",
                "json"
            )
                .directory(File(workingDir))
                .redirectErrorStream(true)
            KiroCliResolver.configureProcessBuilder(processBuilder)
            val process = processBuilder.start()
            if (!process.waitFor(SESSION_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return emptyList()
            }
            if (process.exitValue() != 0) return emptyList()

            val root = Json.parseToJsonElement(process.inputStream.bufferedReader().readText()).jsonArray
            root.flatMap { envelope ->
                envelope.jsonObject["sessions"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val session = element.jsonObject
                    val id = session["sessionId"]?.jsonPrimitive?.content.orEmpty()
                    if (id.isBlank()) null else CliSessionInfo(
                        id = id,
                        source = session["source"]?.jsonPrimitive?.content.orEmpty(),
                        updatedAt = session["updatedAt"]?.jsonPrimitive?.content.orEmpty()
                    )
                }
            }
        } catch (e: Exception) {
            log.debug("Unable to list kiro-cli sessions", e)
            emptyList()
        }
    }

    fun getConsecutiveErrors(): Int = consecutiveErrors.get()

    private data class CliSessionInfo(val id: String, val source: String, val updatedAt: String)

    companion object {
        private const val SESSION_LIST_TIMEOUT_SECONDS = 1L
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[a-zA-Z]|\u001B\\[\\?[0-9]*[a-zA-Z]|\u001B\\[[0-9]*G")
        private val AGENT_FALLBACK_WARNING = Regex(
            "^(?:WARNING:\\s*)?agent\\s+.+\\s+not found,\\s+using\\s+.+$",
            RegexOption.IGNORE_CASE
        )

        internal fun buildChatCommand(
            cliPath: String,
            message: String,
            resumeId: String?,
            isFirstMessage: Boolean,
            model: String,
            trustAllTools: Boolean
        ): List<String> = buildList {
            addAll(listOf(cliPath, "chat", "--v2", "--no-interactive"))
            if (trustAllTools) add("--trust-all-tools")

            if (resumeId != null) {
                addAll(listOf("--resume-id", resumeId))
            } else if (!isFirstMessage) {
                add("--resume")
            }

            // kiro-cli의 모델 id는 소문자 "auto" — 레거시 저장값 "Auto"도 함께 처리
            if (!model.equals("auto", ignoreCase = true)) {
                addAll(listOf("--model", model))
            }
            add(message)
        }

        fun stripAnsi(text: String): String {
            return ANSI_REGEX.replace(text, "")
                .replace("\r", "")
                .let { s ->
                    if (s.startsWith("> ")) s.substring(2) else s
                }
        }

        // 스피너/진행 표시 문자 (braille ⣾⣽…, 블록 ▰▱, 회전 문자)와 공백 — 프레임 비교용
        private val SPINNER_GLYPHS = Regex("[\\u2800-\\u28FF▰▱◐◓◑◒]|\\s+")

        /** 스피너 문자와 공백을 제거한 비교용 문자열. 애니메이션 프레임끼리는 같은 값이 된다. */
        fun normalizeSystemLine(s: String): String = SPINNER_GLYPHS.replace(s, "")

        /**
         * ANSI 원본 기반으로 시스템 로그 판별.
         * kiro-cli 출력 패턴:
         * - (using tool: xxx) → 도구 사용 표시
         * - ^[[38;5;244m → 회색 텍스트 (시스템 메시지)
         * - Completed in → 완료 시간
         * - Loading... → 로딩 스피너
         * - ▸ Time: → 실행 시간
         * - ^[[?25l / ^[[?25h → 커서 숨김/표시 (터미널 제어)
         * - ------  → 구분선
         * - ^M → 캐리지 리턴 (스피너 업데이트)
         */
        fun isSystemOutput(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            
            // 터미널 제어 시퀀스만 있는 줄
            val cleaned = stripAnsi(trimmed)
            if (cleaned.isBlank()) return true
            
            // (using tool: xxx) 패턴
            if (raw.contains("(using tool:")) return true
            // Completed in 패턴
            if (cleaned.startsWith("- Completed in")) return true
            // trust-all-tools 경고 메시지
            if (cleaned.startsWith("All tools are now trusted")) return true
            // Agents can sometimes 경고
            if (cleaned.startsWith("Agents can sometimes")) return true
            // 존재하지 않는 이전 agent 이름을 기본 agent로 대체했다는 경고
            if (AGENT_FALLBACK_WARNING.matches(cleaned)) return true
            // Learn more at 링크
            if (cleaned.startsWith("Learn more at https://")) return true
            // 로딩 스피너 (캐리지 리턴 포함)
            if (raw.contains("\r")) return true
            // 시간 표시
            if (cleaned.contains("▸ Time:")) return true
            // 구분선
            if (cleaned.matches(Regex("^-{3,}$"))) return true
            // 도구 출력: 들여쓰기된 번호 목록 (  1. Function xxx at ...)
            if (cleaned.matches(Regex("^\\s+\\d+\\.\\s+(Function|Class|Method|Interface|Object|Property)\\s.*at\\s.*:\\d+:\\d+\\s*$"))) return true
            // Found X of Y symbols
            if (cleaned.matches(Regex("^Found \\d+ of \\d+ symbols.*$"))) return true
            // Searching for symbols
            if (cleaned.startsWith("Searching for symbols")) return true
            // Looking up symbols
            if (cleaned.startsWith("Looking up symbols")) return true
            // kiro-cli 시작 메시지 (회색 ANSI: 38;5;244)
            if (raw.contains("\u001B[38;5;244m") && !raw.contains("\u001B[38;5;10m")) return true
            // > 프롬프트
            if (cleaned.matches(Regex("^>\\s*$"))) return true
            // 커서 제어만 있는 줄
            if (raw.contains("\u001B[?25l") || raw.contains("\u001B[?25h")) {
                if (cleaned.length < 5) return true
            }
            
            return false
        }

        fun classifyExitCode(exitCode: Int, hasOutput: Boolean): String {
            return when {
                exitCode == 1 && !hasOutput -> "kiro-cli execution failed. Check authentication status. (/status)"
                exitCode == 1 -> "kiro-cli exited with error (code: $exitCode)"
                exitCode == 2 -> "kiro-cli command arguments are invalid."
                exitCode == 126 -> "No permission to execute kiro-cli. Check file permissions."
                exitCode == 127 -> "kiro-cli not found. Check installation path."
                exitCode == 130 -> "Interrupted by user."
                exitCode == 137 -> "kiro-cli process was forcibly terminated (possibly out of memory)."
                exitCode == 143 -> "kiro-cli process received termination signal."
                else -> "kiro-cli exited abnormally (exit code: $exitCode)"
            }
        }

        fun classifyException(e: Exception): String {
            val message = e.message ?: ""
            return when {
                e is java.io.IOException && message.contains("No such file", ignoreCase = true) ->
                    "kiro-cli not found. Check the path in settings."
                e is java.io.IOException && message.contains("Permission denied", ignoreCase = true) ->
                    "No permission to execute kiro-cli."
                e is java.io.IOException ->
                    "I/O error while running kiro-cli: $message"
                e is InterruptedException ->
                    "Request was interrupted."
                else ->
                    "Unexpected error: $message"
            }
        }
    }
}

/**
 * kiro-cli 출력 스트림을 줄/스피너 프레임 단위로 분리해 콜백으로 전달.
 * - '\n': 일반 줄 경계
 * - '\r' 단독: 스피너 애니메이션의 프레임 경계 (같은 줄 덮어쓰기)
 * - '\r\n': 일반 개행 (Windows)
 * 연속된 시스템 메시지가 스피너 문자만 다르면 한 번만 전달한다
 * (로그아웃 상태의 "Opening browser..." 스피너가 프레임마다 쌓이는 문제 방지).
 */
internal class OutputEmitter(private val onChunk: (String) -> Unit) {

    var hasOutput = false
        private set

    private val lineBuffer = StringBuilder()
    private var crPending = false
    private var lastSysNormalized: String? = null

    fun feed(text: String) {
        for (c in text) {
            if (crPending) {
                crPending = false
                if (c != '\n') {
                    // '\r' 단독 → 지금까지의 버퍼가 하나의 스피너 프레임
                    emit(lineBuffer.toString() + "\r")
                    lineBuffer.clear()
                }
                // '\r\n'이면 일반 개행으로 아래에서 처리
            }
            if (c == '\r') {
                crPending = true
                continue
            }
            lineBuffer.append(c)
            if (c == '\n') {
                emit(lineBuffer.toString())
                lineBuffer.clear()
            }
        }
        if (lineBuffer.length > 200) {
            emit(lineBuffer.toString())
            lineBuffer.clear()
        }
    }

    fun finish() {
        if (crPending) {
            crPending = false
            emit(lineBuffer.toString() + "\r")
            lineBuffer.clear()
        }
        if (lineBuffer.isNotEmpty()) {
            emit(lineBuffer.toString())
            lineBuffer.clear()
        }
    }

    private fun emit(raw: String) {
        val clean = KiroCliProcess.stripAnsi(raw)
        if (clean.isBlank()) return

        if (KiroCliProcess.isSystemOutput(raw)) {
            val normalized = KiroCliProcess.normalizeSystemLine(clean)
            if (normalized == lastSysNormalized) return // 같은 내용의 스피너 프레임 반복 억제
            lastSysNormalized = normalized
            onChunk("[SYS]" + clean)
        } else {
            lastSysNormalized = null
            onChunk(clean)
        }
        hasOutput = true
    }
}
