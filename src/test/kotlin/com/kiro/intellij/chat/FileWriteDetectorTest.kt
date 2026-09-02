package com.kiro.intellij.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * kiro-cli 2.18.1이 실제로 출력한 줄들로 검증한다 (ANSI 제거 후 형태).
 * 오탐이 나면 되돌리기 버튼이 엉뚱한 경로를 가리키므로, 아닌 것을 걸러내는 쪽 테스트가 더 중요하다.
 */
class FileWriteDetectorTest {

    @Test
    fun `detects write tool announcement`() {
        assertEquals(
            "/tmp/kiro-cli-probe/notas.txt",
            FileWriteDetector.extractPath(
                "I'll create the following file: /tmp/kiro-cli-probe/notas.txt (using tool: write)"
            )
        )
    }

    @Test
    fun `detects the Creating action line`() {
        assertEquals(
            "/tmp/kiro-cli-probe/notas.txt",
            FileWriteDetector.extractPath("Creating: /tmp/kiro-cli-probe/notas.txt")
        )
    }

    @Test
    fun `detects update and write verbs`() {
        assertEquals("src/Main.java", FileWriteDetector.extractPath("Updating: src/Main.java"))
        assertEquals("src/Main.java", FileWriteDetector.extractPath("Writing: src/Main.java"))
        assertEquals("a/b/c.kt", FileWriteDetector.extractPath("Replacing: a/b/c.kt"))
    }

    @Test
    fun `detects fs_write and edit tool names`() {
        assertEquals(
            "/p/File.kt",
            FileWriteDetector.extractPath("I will update the following file: /p/File.kt (using tool: fs_write)")
        )
        assertEquals(
            "/p/File.kt",
            FileWriteDetector.extractPath("Editing file: /p/File.kt (using tool: edit)")
        )
    }

    @Test
    fun `ignores read-only tools`() {
        assertNull(
            FileWriteDetector.extractPath(
                "Reading file: /tmp/kiro-cli-probe/src/Main.java, all lines (using tool: read)"
            )
        )
        assertNull(
            FileWriteDetector.extractPath("Searching for symbols matching: \"main\" (using tool: code)")
        )
        assertNull(
            FileWriteDetector.extractPath("I will run the following command: ls -la (using tool: shell)")
        )
    }

    @Test
    fun `ignores timing and prose lines`() {
        assertNull(FileWriteDetector.extractPath(" - Completed in 0.4s"))
        assertNull(FileWriteDetector.extractPath("Purpose: Crear notas.txt con la palabra ok"))
        assertNull(FileWriteDetector.extractPath(""))
        assertNull(FileWriteDetector.extractPath("   "))
        assertNull(FileWriteDetector.extractPath("total 44"))
    }

    @Test
    fun `ignores a purpose sentence that has no path`() {
        // 'Creating: something' 형태지만 경로가 아니면 버린다
        assertNull(FileWriteDetector.extractPath("Creating: a new user in the database"))
    }

    @Test
    fun `strips quotes and trailing description`() {
        assertEquals("/p/a b.txt", FileWriteDetector.extractPath("Creating: \"/p/a b.txt\""))
        assertEquals("/p/File.kt", FileWriteDetector.extractPath("Creating: /p/File.kt, 3 lines"))
    }

    @Test
    fun `handles windows paths`() {
        assertEquals(
            "C:\\proj\\src\\Main.java",
            FileWriteDetector.extractPath("Creating: C:\\proj\\src\\Main.java")
        )
    }

    // --- absoluteCandidate: el CLI mezcla rutas absolutas y relativas al proyecto ---

    @Test
    fun `relative paths resolve against the project root`() {
        // este era el bug: 'src/main/...' no lo encontraba el VFS, que exige ruta absoluta
        assertEquals(
            "/home/u/proj/src/main/java/com/acme/Enum.java",
            FileWriteDetector.absoluteCandidate("src/main/java/com/acme/Enum.java", "/home/u/proj", "/home/u")
        )
        assertEquals(
            "/home/u/proj/README.md",
            FileWriteDetector.absoluteCandidate("./README.md", "/home/u/proj", "/home/u")
        )
    }

    @Test
    fun `absolute paths are left untouched`() {
        assertEquals(
            "/tmp/x/notas.txt",
            FileWriteDetector.absoluteCandidate("/tmp/x/notas.txt", "/home/u/proj", "/home/u")
        )
    }

    @Test
    fun `tilde expands to the home directory`() {
        assertEquals("/home/u/notes.md", FileWriteDetector.absoluteCandidate("~/notes.md", "/home/u/proj", "/home/u"))
        assertEquals("/home/u", FileWriteDetector.absoluteCandidate("~", "/home/u/proj", "/home/u"))
    }

    @Test
    fun `windows and unc paths are treated as absolute`() {
        assertEquals(
            "C:\\proj\\Main.java",
            FileWriteDetector.absoluteCandidate("C:\\proj\\Main.java", "C:\\proj", "C:\\Users\\u")
        )
        assertEquals(
            "\\\\server\\share\\f.txt",
            FileWriteDetector.absoluteCandidate("\\\\server\\share\\f.txt", "C:\\proj", "C:\\Users\\u")
        )
    }

    @Test
    fun `trailing slash in the base path does not double up`() {
        assertEquals(
            "/home/u/proj/a.txt",
            FileWriteDetector.absoluteCandidate("a.txt", "/home/u/proj/", "/home/u")
        )
    }

    @Test
    fun `without a project root the path is left as is`() {
        assertEquals("a.txt", FileWriteDetector.absoluteCandidate("a.txt", "", "/home/u"))
    }
}
