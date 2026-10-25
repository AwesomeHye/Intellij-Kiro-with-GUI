package com.kiro.intellij.chat

import com.intellij.openapi.project.Project
import com.kiro.intellij.settings.KiroSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class KiroCliProcessTest {

    private fun mockCliWith(script: File) {
        mockkObject(KiroSettings.Companion)
        val settingsMock = mockk<KiroSettings>()
        every { settingsMock.state } returns KiroSettings.State(kiroCommand = script.absolutePath)
        every { KiroSettings.getInstance() } returns settingsMock

        mockkObject(KiroCliValidator)
        every { KiroCliValidator.validate(any()) } returns KiroCliValidator.ValidationResult(
            cliFound = true, cliPath = script.absolutePath,
            version = "test", authenticated = true, errorMessage = null
        )
    }

    @Test
    fun `chat command should not trust all tools by default`() {
        val command = KiroCliProcess.buildChatCommand(
            cliPath = "/path/to/kiro-cli",
            message = "hello",
            resumeId = null,
            isFirstMessage = true,
            model = "Auto",
            trustAllTools = false
        )

        assertFalse(command.contains("--trust-all-tools"))
        assertEquals("hello", command.last())
    }

    @Test
    fun `chat command should trust all tools only when enabled`() {
        val command = KiroCliProcess.buildChatCommand(
            cliPath = "/path/to/kiro-cli",
            message = "hello",
            resumeId = "session-1",
            isFirstMessage = false,
            model = "claude-sonnet-4.5",
            trustAllTools = true
        )

        assertTrue(command.contains("--trust-all-tools"))
        assertTrue(command.containsAll(listOf("--resume-id", "session-1", "--model", "claude-sonnet-4.5")))
        assertEquals("hello", command.last())
    }

    @Test
    fun `stopped run does not emit late done or chunks`(@TempDir tempDir: Path) {
        val script = File(tempDir.toFile(), "fake-kiro").apply {
            writeText("#!/bin/sh\necho first line\nsleep 5\necho late line\n")
            setExecutable(true)
        }
        mockCliWith(script)

        try {
            val events = CopyOnWriteArrayList<String>()
            val project = mockk<Project>(relaxed = true)
            every { project.basePath } returns tempDir.toString()
            val proc = KiroCliProcess(project)
            proc.sendMessage(
                "hello",
                onChunk = { events.add("chunk:${it.trim()}") },
                onDone = { events.add("done") },
                onError = { events.add("error:$it") }
            )

            // 첫 출력 도착 대기
            val deadline = System.currentTimeMillis() + 5000
            while (events.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(events.isNotEmpty(), "first chunk should arrive")

            // 중지: 이후 읽기 스레드의 finally가 늦게 실행되어도 done/chunk가 새면 안 됨
            proc.stop()
            val sizeAtStop = events.size
            Thread.sleep(2000)

            assertEquals(sizeAtStop, events.size, "stopped run must not emit late events: $events")
            assertTrue(events.none { it == "done" }, "late done must be suppressed: $events")
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `normal run emits chunks and a single done`(@TempDir tempDir: Path) {
        val script = File(tempDir.toFile(), "fake-kiro").apply {
            writeText("#!/bin/sh\necho the answer\n")
            setExecutable(true)
        }
        mockCliWith(script)

        try {
            val events = CopyOnWriteArrayList<String>()
            val project = mockk<Project>(relaxed = true)
            every { project.basePath } returns tempDir.toString()
            val proc = KiroCliProcess(project)
            proc.sendMessage(
                "hello",
                onChunk = { events.add("chunk:${it.trim()}") },
                onDone = { events.add("done") },
                onError = { events.add("error:$it") }
            )

            val deadline = System.currentTimeMillis() + 10000
            while (!events.contains("done") && System.currentTimeMillis() < deadline) Thread.sleep(50)

            assertTrue(events.contains("chunk:the answer"), "answer chunk should arrive: $events")
            assertEquals(1, events.count { it == "done" }, "exactly one done: $events")
            assertTrue(events.none { it.startsWith("error") }, "no errors expected: $events")
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `spinner frames separated by carriage return are emitted once`() {
        val chunks = mutableListOf<String>()
        val emitter = OutputEmitter { chunks.add(it) }

        // 로그아웃 상태에서 kiro-cli가 뿜는 로그인 스피너: \r로 같은 줄을 덮어쓰는 프레임들
        emitter.feed("⡆ Opening browser... | Press (^) + C to cancel\r")
        emitter.feed("⡇ Opening browser... | Press (^) + C to cancel\r")
        emitter.feed("⡏ Opening browser... | Press (^) + C to cancel\r")
        emitter.feed("▰▰▱▱▱▱▱ Opening browser... | Press (^) + C to cancel\r")
        emitter.feed("▰▰▰▱▱▱▱ Opening browser... | Press (^) + C to cancel\r")
        emitter.finish()

        assertEquals(1, chunks.size, "same spinner message should be emitted only once: $chunks")
        assertTrue(chunks[0].startsWith("[SYS]"))
        assertTrue(chunks[0].contains("Opening browser"))
    }

    @Test
    fun `different system messages are not deduplicated`() {
        val chunks = mutableListOf<String>()
        val emitter = OutputEmitter { chunks.add(it) }

        emitter.feed("⡆ Opening browser... | Press (^) + C to cancel\r")
        emitter.feed("⡆ Loading something else entirely\r")
        emitter.finish()

        assertEquals(2, chunks.size)
    }

    @Test
    fun `normal newline lines pass through unchanged`() {
        val chunks = mutableListOf<String>()
        val emitter = OutputEmitter { chunks.add(it) }

        emitter.feed("hello world\n")
        emitter.feed("second line\n")
        emitter.finish()

        assertEquals(listOf("hello world\n", "second line\n"), chunks)
        assertTrue(emitter.hasOutput)
    }

    @Test
    fun `crlf is treated as a normal newline not a spinner frame`() {
        val chunks = mutableListOf<String>()
        val emitter = OutputEmitter { chunks.add(it) }

        emitter.feed("windows line\r\n")
        emitter.finish()

        assertEquals(1, chunks.size)
        assertFalse(chunks[0].startsWith("[SYS]"), "CRLF line must not be classified as spinner output: ${chunks[0]}")
    }

    @Test
    fun `content after final spinner frame is emitted as normal chunk`() {
        val chunks = mutableListOf<String>()
        val emitter = OutputEmitter { chunks.add(it) }

        emitter.feed("⡆ Opening browser... | Press (^) + C to cancel\r")
        emitter.feed("⡇ Opening browser... | Press (^) + C to cancel\r")
        emitter.feed("✓ Signed in with Google\n")
        emitter.feed("actual answer\n")
        emitter.finish()

        assertTrue(chunks.any { it.contains("Signed in with Google") })
        assertTrue(chunks.any { it == "actual answer\n" })
    }

    @Test
    fun `normalizeSystemLine equates spinner frames`() {
        val a = KiroCliProcess.normalizeSystemLine("⡆ Opening browser... | Press (^) + C to cancel")
        val b = KiroCliProcess.normalizeSystemLine("⡏ Opening browser... | Press (^) + C to cancel")
        val c = KiroCliProcess.normalizeSystemLine("▰▰▰▱▱▱▱ Opening browser... | Press (^) + C to cancel")
        assertEquals(a, b)
        assertEquals(a, c)

        val other = KiroCliProcess.normalizeSystemLine("⡆ Loading tools...")
        assertTrue(a != other)
    }

    @Test
    fun `stripAnsi should remove ANSI escape codes`() {
        assertEquals("hello", KiroCliProcess.stripAnsi("\u001B[32mhello\u001B[0m"))
        assertEquals("hello world", KiroCliProcess.stripAnsi("\u001B[1;34mhello\u001B[0m \u001B[31mworld\u001B[0m"))
        assertEquals("plain text", KiroCliProcess.stripAnsi("plain text"))
    }

    @Test
    fun `stripAnsi should remove carriage returns`() {
        assertEquals("hello", KiroCliProcess.stripAnsi("hello\r"))
        assertEquals("line1\nline2", KiroCliProcess.stripAnsi("line1\r\nline2"))
    }

    @Test
    fun `stripAnsi should remove leading prompt marker`() {
        assertEquals("hello", KiroCliProcess.stripAnsi("> hello"))
        assertEquals("not a prompt > here", KiroCliProcess.stripAnsi("not a prompt > here"))
    }

    @Test
    fun `stripAnsi should handle cursor position codes`() {
        assertEquals("text", KiroCliProcess.stripAnsi("\u001B[?25htext"))
        assertEquals("text", KiroCliProcess.stripAnsi("\u001B[10Gtext"))
    }

    @Test
    fun `classifyExitCode 1 without output should suggest auth check`() {
        val msg = KiroCliProcess.classifyExitCode(1, hasOutput = false)
        assertTrue(msg.contains("authentication") || msg.contains("Check"), "Should mention authentication: $msg")
    }

    @Test
    fun `classifyExitCode 1 with output should mention error`() {
        val msg = KiroCliProcess.classifyExitCode(1, hasOutput = true)
        assertTrue(msg.contains("error"), "Should mention error: $msg")
    }

    @Test
    fun `classifyExitCode 126 should mention permission`() {
        val msg = KiroCliProcess.classifyExitCode(126, hasOutput = false)
        assertTrue(msg.contains("permission") || msg.contains("Permission"), "Should mention permission: $msg")
    }

    @Test
    fun `classifyExitCode 127 should mention not found`() {
        val msg = KiroCliProcess.classifyExitCode(127, hasOutput = false)
        assertTrue(msg.contains("not found"), "Should mention not found: $msg")
    }

    @Test
    fun `classifyExitCode 130 should mention user interruption`() {
        val msg = KiroCliProcess.classifyExitCode(130, hasOutput = false)
        assertTrue(msg.contains("Interrupted") || msg.contains("interrupted"), "Should mention interruption: $msg")
    }

    @Test
    fun `classifyExitCode 137 should mention OOM`() {
        val msg = KiroCliProcess.classifyExitCode(137, hasOutput = false)
        assertTrue(msg.contains("terminated") || msg.contains("memory"), "Should mention kill/OOM: $msg")
    }

    @Test
    fun `classifyExitCode unknown should show exit code`() {
        val msg = KiroCliProcess.classifyExitCode(42, hasOutput = false)
        assertTrue(msg.contains("42"), "Should contain exit code: $msg")
    }

    @Test
    fun `classifyException for IOException with No such file`() {
        val e = java.io.IOException("No such file or directory")
        val msg = KiroCliProcess.classifyException(e)
        assertTrue(msg.contains("not found"), "Should mention not found: $msg")
    }

    @Test
    fun `classifyException for IOException with Permission denied`() {
        val e = java.io.IOException("Permission denied")
        val msg = KiroCliProcess.classifyException(e)
        assertTrue(msg.contains("permission") || msg.contains("Permission"), "Should mention permission: $msg")
    }

    @Test
    fun `classifyException for InterruptedException`() {
        val e = InterruptedException("thread interrupted")
        val msg = KiroCliProcess.classifyException(e)
        assertTrue(msg.contains("interrupted") || msg.contains("Interrupted"), "Should mention interruption: $msg")
    }

    @Test
    fun `classifyException for generic IOException`() {
        val e = java.io.IOException("Connection reset")
        val msg = KiroCliProcess.classifyException(e)
        assertTrue(msg.contains("I/O"), "Should mention I/O: $msg")
    }

    @Test
    fun `classifyException for unknown exception`() {
        val e = RuntimeException("something went wrong")
        val msg = KiroCliProcess.classifyException(e)
        assertTrue(msg.contains("Unexpected") || msg.contains("unexpected"), "Should mention unexpected: $msg")
    }
}
