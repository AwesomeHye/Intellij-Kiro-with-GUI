package com.kiro.intellij.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 파일 검색 로직 테스트.
 * 예전 버전은 필터/정렬을 테스트 안에 복사해 두고 그 복사본을 검증했다(= 프로덕션 코드는
 * 한 줄도 실행되지 않았다). 지금은 ChatBackendServer가 실제로 쓰는 ProjectFileSearch를 직접 호출한다.
 */
class FileSearchTest {

    private fun info(name: String, path: String) =
        ProjectFileInfo(name, path, path.substringBeforeLast("/", ""), path.substringAfterLast('.', ""))

    private fun filter(files: List<ProjectFileInfo>, query: String) =
        files.filter { ProjectFileSearch.matches(it, query) }

    @Test
    fun `filter files by name`() {
        val files = listOf(
            info("ChatPanel.kt", "src/main/kotlin/chat/ChatPanel.kt"),
            info("ChatSession.kt", "src/main/kotlin/chat/ChatSession.kt"),
            info("Settings.kt", "src/main/kotlin/settings/Settings.kt")
        )

        val matches = filter(files, "chat")

        // Settings.kt도 경로에 'chat'이 없으니 제외된다
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.name.lowercase().contains("chat") })
    }

    @Test
    fun `filter files by path`() {
        val files = listOf(
            info("Panel.kt", "src/main/kotlin/chat/Panel.kt"),
            info("Panel.kt", "src/main/kotlin/settings/Panel.kt")
        )

        val matches = filter(files, "settings")

        assertEquals(1, matches.size)
        assertEquals("src/main/kotlin/settings/Panel.kt", matches[0].path)
    }

    @Test
    fun `sort by name match priority`() {
        val files = listOf(
            info("AChat.kt", "src/AChat.kt"),
            info("ChatPanel.kt", "src/ChatPanel.kt"),
            info("MyChat.kt", "src/MyChat.kt")
        )

        val sorted = ProjectFileSearch.sort(files, "chat")

        assertEquals("ChatPanel.kt", sorted[0].name)
    }

    @Test
    fun `shorter names win when both start with the query`() {
        val files = listOf(
            info("ChatBackendServer.kt", "src/ChatBackendServer.kt"),
            info("Chat.kt", "src/Chat.kt")
        )

        val sorted = ProjectFileSearch.sort(files, "chat")

        assertEquals("Chat.kt", sorted[0].name)
    }

    @Test
    fun `empty query matches everything and sorts by name`() {
        val files = listOf(info("B.kt", "src/B.kt"), info("A.kt", "src/A.kt"))

        assertEquals(2, filter(files, "").size)
        assertEquals("A.kt", ProjectFileSearch.sort(files, "").first().name)
    }

    @Test
    fun `case insensitive search`() {
        val files = listOf(
            info("ChatPanel.kt", "src/ChatPanel.kt"),
            info("chatSession.kt", "src/chatSession.kt")
        )

        assertEquals(2, filter(files, "CHAT").size)
    }

    @Test
    fun `fuzzy match partial name`() {
        val files = listOf(
            info("ChatBackendServer.kt", "src/ChatBackendServer.kt"),
            info("ChatPanel.kt", "src/ChatPanel.kt")
        )

        val matches = filter(files, "backend")

        assertEquals(1, matches.size)
        assertEquals("ChatBackendServer.kt", matches[0].name)
    }

    @Test
    fun `path queries bypass the name index`() {
        // 'com/acme'처럼 '/'가 있으면 파일명 인덱스로는 못 찾으므로 전체 순회로 내려가야 한다
        assertTrue(ProjectFileSearch.isPathQuery("com/acme"))
        assertTrue(ProjectFileSearch.isPathQuery("src/main"))
        assertFalse(ProjectFileSearch.isPathQuery("MainController"))
        assertFalse(ProjectFileSearch.isPathQuery(""))
    }

    @Test
    fun `name candidates are ranked by prefix then length`() {
        val names = listOf("MyMainWindow.java", "Main.java", "MainController.java")

        val sorted = ProjectFileSearch.sortNames(names, "main")

        assertEquals(listOf("Main.java", "MainController.java", "MyMainWindow.java"), sorted)
    }

    @Test
    fun `relativePath strips the project base path`() {
        assertEquals(
            "src/main/java/Main.java",
            ProjectFileSearch.relativePath("/home/u/proj/src/main/java/Main.java", "/home/u/proj")
        )
        // 프로젝트 밖의 파일은 절대 경로를 그대로 둔다
        assertEquals("/opt/other/File.kt", ProjectFileSearch.relativePath("/opt/other/File.kt", "/home/u/proj"))
    }

    @Test
    fun `toInfo derives dir and extension`() {
        val i = ProjectFileSearch.toInfo("Main.java", "/home/u/proj/src/com/acme/Main.java", "java", "/home/u/proj")

        assertEquals("Main.java", i.name)
        assertEquals("src/com/acme/Main.java", i.path)
        assertEquals("src/com/acme", i.dir)
        assertEquals("java", i.ext)
    }

    @Test
    fun `toInfo handles a file at the project root`() {
        val i = ProjectFileSearch.toInfo("README.md", "/home/u/proj/README.md", "md", "/home/u/proj")

        assertEquals("", i.dir)
        assertEquals("README.md", i.path)
    }

    @Test
    fun `compiled class files are never listed`() {
        // el motivo: .class sale junto a su .java y duplica la lista, y el CLI no lo puede leer
        assertFalse(ProjectFileSearch.isMentionable("class", true))
        assertFalse(ProjectFileSearch.isMentionable("class", false)) // aunque el IDE no lo marque binario
        assertFalse(ProjectFileSearch.isMentionable("CLASS", false)) // case insensitive
        assertTrue(ProjectFileSearch.isMentionable("java", false))
        assertTrue(ProjectFileSearch.isMentionable("kt", false))
    }

    @Test
    fun `archives and native artifacts are not listed`() {
        listOf("jar", "war", "ear", "aar", "so", "dll", "dylib", "exe", "pyc", "zip", "gz")
            .forEach { assertFalse(ProjectFileSearch.isMentionable(it, false), "$it should be filtered out") }
    }

    @Test
    fun `binary files are not listed even with an unknown extension`() {
        assertFalse(ProjectFileSearch.isMentionable("weird", true))
        assertTrue(ProjectFileSearch.isMentionable("weird", false))
    }

    @Test
    fun `text files without extension stay listed`() {
        // Dockerfile, Makefile, LICENSE...
        assertTrue(ProjectFileSearch.isMentionable(null, false))
        assertTrue(ProjectFileSearch.isMentionable("", false))
    }

    @Test
    fun `limit constants keep the payload bounded`() {
        assertTrue(ProjectFileSearch.RESULT_LIMIT in 1..200)
        assertTrue(ProjectFileSearch.NAME_CANDIDATE_LIMIT >= ProjectFileSearch.RESULT_LIMIT)
    }
}
