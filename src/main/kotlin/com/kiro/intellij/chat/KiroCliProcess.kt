package com.kiro.intellij.chat

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kiro.intellij.settings.KiroCliResolver
import com.kiro.intellij.settings.KiroSettings
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * kiro-cli를 매 메시지마다 --no-interactive로 실행.
 * --resume으로 대화를 이어간다.
 * 출력을 실시간 스트리밍하여 콜백으로 전달.
 */
class KiroCliProcess(private val project: Project) {

    private val log = Logger.getInstance(KiroCliProcess::class.java)
    private var isFirstMessage = true
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
                val command = mutableListOf(cliPath, "chat", "--no-interactive", "--trust-all-tools")

                if (!isFirstMessage) {
                    command.add("--resume")
                }

                val effectiveModel = model ?: settings.defaultModel
                // kiro-cli의 모델 id는 소문자 "auto" — 레거시 저장값 "Auto"도 함께 처리
                if (!effectiveModel.equals("auto", ignoreCase = true)) {
                    command.addAll(listOf("--model", effectiveModel))
                }

                command.add(message)

                val workingDir = project.basePath ?: System.getProperty("user.home")
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
                    isFirstMessage = false

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
        isFirstMessage = true
        consecutiveErrors.set(0)
    }

    fun getConsecutiveErrors(): Int = consecutiveErrors.get()

    companion object {
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[a-zA-Z]|\u001B\\[\\?[0-9]*[a-zA-Z]|\u001B\\[[0-9]*G")

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
         * 도구 블록 시작 줄. kiro-cli는 도구를 쓸 때마다 `... (using tool: xxx)` 를 먼저 출력한다.
         * 그 뒤에 오는 줄들(Purpose:, 명령 표준출력, 심볼 목록, diff 등)은 도구 활동이지 답변이 아니다.
         */
        fun startsToolBlock(raw: String): Boolean = raw.contains("(using tool:")

        /** 도구 블록 종료 줄: ` - Completed in 0.4s` */
        fun endsToolBlock(clean: String): Boolean = clean.trim().startsWith("- Completed in")

        /**
         * 어시스턴트 본문 줄. kiro-cli는 모델이 말하는 줄에만 `> ` 프롬프트 마커를 붙이므로
         * 도구 출력과 답변을 구분하는 가장 확실한 신호다. (마커만 있는 빈 줄은 제외)
         */
        fun isAssistantLine(raw: String): Boolean {
            val noAnsi = ANSI_REGEX.replace(raw, "").replace("\r", "")
            return noAnsi.startsWith("> ") && noAnsi.trim().length > 1
        }

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

    /** `(using tool:)` 이후 `- Completed in` 까지의 줄은 도구 활동으로 취급한다. */
    private var inToolBlock = false
    private var toolBlockLines = 0

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

        // 어시스턴트 본문('> ' 마커)이 오면 도구 블록은 끝난 것으로 본다.
        val assistant = KiroCliProcess.isAssistantLine(raw)
        if (assistant) {
            inToolBlock = false
            toolBlockLines = 0
        }

        // 도구 블록 안의 줄은 패턴에 안 걸려도 활동(=시스템)으로 분류한다.
        // 답변을 삼키는 사고를 막기 위해 블록 길이에 상한을 둔다.
        val sys = !assistant && (KiroCliProcess.isSystemOutput(raw) || inToolBlock)

        if (KiroCliProcess.startsToolBlock(raw)) {
            inToolBlock = true
            toolBlockLines = 0
        } else if (inToolBlock) {
            if (KiroCliProcess.endsToolBlock(clean) || ++toolBlockLines > MAX_TOOL_BLOCK_LINES) {
                inToolBlock = false
                toolBlockLines = 0
            }
        }

        if (sys) {
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

    private companion object {
        /** 도구 블록이 비정상적으로 길어지면(종료 줄 미출력 등) 답변을 삼키지 않도록 강제 종료 */
        const val MAX_TOOL_BLOCK_LINES = 500
    }
}
