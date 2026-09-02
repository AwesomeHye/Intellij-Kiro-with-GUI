package com.kiro.intellij.chat

/**
 * kiro-cli 출력에서 "에이전트가 이 파일을 썼다"는 신호와 경로를 뽑아낸다.
 *
 * `--trust-all-tools`로 돌기 때문에 CLI는 확인 없이 파일을 이미 써 버린다. 그래서 사전 승인은
 * 불가능하고(비대화형 모드에서 CLI는 승인을 요청하지 않고 거부한다), 대신 무엇이 바뀌었는지
 * 보여주고 되돌릴 수 있게 한다. 이 객체는 그 "무엇"을 찾는 부분이다.
 *
 * kiro-cli 2.18.1이 실제로 출력하는 형태:
 *   I'll create the following file: /abs/path/notas.txt (using tool: write)
 *   Creating: /abs/path/notas.txt
 *   Updating: /abs/path/Main.java
 */
internal object FileWriteDetector {

    /** 쓰기 계열 도구 이름 (announcement 줄의 `(using tool: X)`) */
    private val WRITE_TOOLS = setOf("write", "fs_write", "edit", "str_replace", "create")

    private val ANNOUNCEMENT = Regex("""^(.*?):\s*(\S[^()]*?)\s*\(using tool:\s*([^)]+)\)\s*$""")

    private val ACTION_LINE = Regex(
        """^(?:Creating|Updating|Writing|Appending|Replacing|Created|Updated|Wrote)\s*:\s*(\S.*?)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * ANSI가 제거된 한 줄에서 쓰기 대상 경로를 뽑는다. 쓰기 신호가 없으면 null.
     * 경로가 아닌 값(따옴표로 감싼 검색어 등)은 걸러낸다.
     */
    fun extractPath(cleanLine: String): String? {
        val line = cleanLine.trim()
        if (line.isEmpty()) return null

        ANNOUNCEMENT.matchEntire(line)?.let { m ->
            val tool = m.groupValues[3].trim().lowercase()
            if (tool in WRITE_TOOLS) return sanitize(m.groupValues[2])
        }
        ACTION_LINE.matchEntire(line)?.let { m ->
            return sanitize(m.groupValues[1])
        }
        return null
    }

    private fun sanitize(raw: String): String? {
        var path = raw.trim().trim('"', '\'', '`')
        // "…: /path, all lines" 처럼 뒤에 설명이 붙는 경우
        path = path.substringBefore(", ").trim()
        if (path.isEmpty()) return null
        // 경로처럼 보이지 않으면 버린다 (구분자도 없고 확장자도 없는 문장)
        val looksLikePath = path.contains('/') || path.contains('\\') || path.contains('.')
        if (!looksLikePath) return null
        if (path.contains(' ') && !path.startsWith("/") && !path.contains('\\')) return null
        return path
    }

    /**
     * CLI가 찍는 경로는 절대 경로일 때도 있고 프로젝트 기준 상대 경로일 때도 있다
     * (CLI의 작업 디렉터리가 project.basePath이므로 상대 경로는 그 기준이다).
     * VFS 조회는 절대 경로만 받으므로 여기서 절대 경로 후보로 바꾼다.
     */
    fun absoluteCandidate(rawPath: String, basePath: String, homeDir: String): String {
        val path = rawPath.trim()
        return when {
            path.isEmpty() -> path
            path.startsWith("~/") -> homeDir.trimEnd('/') + path.removePrefix("~")
            path == "~" -> homeDir
            path.startsWith("/") -> path
            // Windows: C:\... 또는 \\server\share
            path.length > 2 && path[1] == ':' -> path
            path.startsWith("\\\\") -> path
            basePath.isEmpty() -> path
            else -> basePath.trimEnd('/') + "/" + path.removePrefix("./")
        }
    }
}
