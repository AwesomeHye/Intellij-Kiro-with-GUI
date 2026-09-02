package com.kiro.intellij.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * kiro-cli 2.18.1 (`chat --no-interactive --trust-all-tools`) 실제 출력 기반 회귀 테스트.
 *
 * 도구 announcement 줄과 `- Completed in` 사이의 줄들(Purpose:, 셸 표준출력, diff, 심볼 목록)은
 * 답변이 아니라 활동 로그이므로 [SYS] 로 분류되어야 한다.
 */
class ToolBlockClassificationTest {

    private fun run(vararg raw: String): List<String> {
        val chunks = mutableListOf<String>()
        val emitter = OutputEmitter { chunks.add(it) }
        raw.forEach { emitter.feed(it) }
        emitter.finish()
        return chunks
    }

    private val esc = "\u001B"

    @Test
    fun `shell tool output is activity not answer`() {
        val chunks = run(
            "I will run the following command: $esc[38;5;141mls -la$esc[0m$esc[38;5;244m (using tool: shell)$esc[0m\n",
            "Purpose: Listar contenido del directorio actual\n",
            "total 44\n",
            "drwxrwxr-x  3 rsfranco rsfranco  4096 sep  1 13:43 .\n",
            "$esc[38;5;244m - Completed in 0.4s$esc[0m\n",
            "$esc[38;5;141m> $esc[0mListo.\n"
        )

        assertTrue(chunks.all { it.startsWith("[SYS]") || it == "Listo.\n" }, "chunks=$chunks")
        assertTrue(chunks.any { it.startsWith("[SYS]") && it.contains("(using tool: shell)") })
        assertTrue(chunks.any { it.startsWith("[SYS]") && it.contains("Purpose:") })
        assertTrue(chunks.any { it.startsWith("[SYS]") && it.contains("total 44") })
        assertTrue(chunks.any { it.startsWith("[SYS]") && it.contains("drwxrwxr-x") })
        assertTrue(chunks.any { it.startsWith("[SYS]") && it.contains("- Completed in") })
        assertEquals(1, chunks.count { !it.startsWith("[SYS]") }, "only the '> ' line is answer text")
    }

    @Test
    fun `answer text after a tool block is not swallowed`() {
        val chunks = run(
            "Reading file: $esc[38;5;141m/tmp/x/Main.java$esc[0m, all lines$esc[38;5;244m (using tool: read)$esc[0m\n",
            "$esc[38;5;10m ✓ $esc[0mSuccessfully read $esc[38;5;244m90 bytes$esc[0m from /tmp/x/Main.java\n",
            "$esc[38;5;244m - Completed in 0.0s$esc[0m\n",
            "$esc[38;5;141m> $esc[0mLa clase imprime \"hola\".\n",
            "Segunda linea del mismo parrafo.\n"
        )

        val answers = chunks.filter { !it.startsWith("[SYS]") }
        assertEquals(2, answers.size, "chunks=$chunks")
        assertTrue(answers[0].contains("La clase imprime"))
        assertTrue(answers[1].contains("Segunda linea"))
    }

    @Test
    fun `write tool diff lines are activity`() {
        val chunks = run(
            "I'll create the following file: $esc[38;5;141m/tmp/x/notas.txt$esc[0m$esc[38;5;244m (using tool: write)$esc[0m\n",
            "$esc[49m$esc[38;5;10m+    1$esc[0m:$esc[38;5;10m$esc[49m ok\n",
            "Creating: $esc[38;5;141m/tmp/x/notas.txt$esc[0m\n",
            "$esc[38;5;244m - Completed in 0.0s$esc[0m\n"
        )

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.startsWith("[SYS]") }, "chunks=$chunks")
    }

    @Test
    fun `symbol search results are activity`() {
        val chunks = run(
            "Searching for symbols matching: $esc[38;5;141m\"main\"$esc[0m$esc[38;5;244m (using tool: code)$esc[0m\n",
            "  1. $esc[38;5;12mMethod$esc[0m $esc[38;5;141mmain$esc[0m at $esc[38;5;141msrc/Main.java$esc[0m:$esc[38;5;244m2:3\n",
            "$esc[38;5;244m - Completed in 0.83s$esc[0m\n"
        )

        assertTrue(chunks.all { it.startsWith("[SYS]") }, "chunks=$chunks")
    }

    @Test
    fun `plain answer without any tool stays answer`() {
        val chunks = run(
            "$esc[38;5;141m> $esc[0mHola, no necesito herramientas.\n",
            "Aqui va una tabla:\n",
            "| a | b |\n"
        )

        assertTrue(chunks.none { it.startsWith("[SYS]") }, "chunks=$chunks")
        assertEquals(3, chunks.size)
    }

    @Test
    fun `runaway tool block does not swallow a long answer`() {
        val emitter = mutableListOf<String>()
        val out = OutputEmitter { emitter.add(it) }
        out.feed("Doing something$esc[38;5;244m (using tool: mystery)$esc[0m\n")
        repeat(600) { out.feed("line $it\n") } // no '- Completed in' terminator
        out.finish()

        assertTrue(emitter.any { !it.startsWith("[SYS]") }, "block must be force-closed")
    }

    @Test
    fun `isAssistantLine only matches the prompt marker`() {
        assertTrue(KiroCliProcess.isAssistantLine("$esc[38;5;141m> $esc[0mtexto"))
        assertTrue(KiroCliProcess.isAssistantLine("> texto"))
        assertFalse(KiroCliProcess.isAssistantLine("> "))
        assertFalse(KiroCliProcess.isAssistantLine("total 44"))
        assertFalse(KiroCliProcess.isAssistantLine(" - Completed in 0.4s"))
    }
}
