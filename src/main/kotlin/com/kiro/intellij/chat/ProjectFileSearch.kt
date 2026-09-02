package com.kiro.intellij.chat

/**
 * `@` 자동완성의 파일 검색 모델과 순위 규칙.
 *
 * ChatBackendServer에서 분리한 이유: IDE 인덱스에 의존하지 않는 순수 로직이라
 * 단위 테스트로 직접 검증할 수 있다 (FileSearchTest).
 */
internal data class ProjectFileInfo(
    val name: String,
    val path: String,
    val dir: String,
    val ext: String
)

internal object ProjectFileSearch {

    /** 한 번에 프론트로 보내는 최대 개수 */
    const val RESULT_LIMIT = 50

    /** 이름 인덱스에서 훑어볼 파일명 후보 상한 (거대 모노레포 보호) */
    const val NAME_CANDIDATE_LIMIT = 300

    /** 인덱스를 못 쓸 때(빈 쿼리·경로 쿼리·인덱싱 중) 전체 순회 상한 */
    const val SCAN_LIMIT = 2000

    /**
     * 멘션할 수 없는 확장자. `.class`는 `.java`와 짝으로 나와 목록을 두 배로 만들고,
     * 어차피 CLI가 텍스트로 읽을 수 없다 (컴파일 산출물이지 소스가 아니다).
     * 빌드 출력 폴더가 'excluded'로 표시되지 않은 프로젝트에서도 걸러지도록 확장자로 막는다.
     */
    val NON_MENTIONABLE_EXTENSIONS = setOf(
        "class", "jar", "war", "ear", "aar", "apk", "dex",
        "so", "dll", "dylib", "exe", "o", "a", "lib", "obj",
        "pyc", "pyo", "pyd", "beam", "elc",
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
        "pdb", "idx", "bin"
    )

    /**
     * 목록에 넣을 파일인가. 확장자 블랙리스트 + IDE가 판단한 바이너리 여부.
     * @param isBinary FileType.isBinary (이미지·폰트 등도 여기서 걸린다: 이미지는 첨부 버튼으로 보낸다)
     */
    fun isMentionable(extension: String?, isBinary: Boolean): Boolean {
        val ext = (extension ?: "").lowercase()
        if (ext in NON_MENTIONABLE_EXTENSIONS) return false
        return !isBinary
    }

    /** 쿼리에 '/'가 있으면 파일명이 아니라 경로를 찾는 것이다 → 이름 인덱스로는 못 찾는다 */
    fun isPathQuery(query: String): Boolean = query.contains('/')

    fun matches(file: ProjectFileInfo, query: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        return file.name.lowercase().contains(q) || file.path.lowercase().contains(q)
    }

    /** 파일명이 쿼리로 시작 → 파일명에 포함 → 이름 알파벳 순 */
    fun sort(files: List<ProjectFileInfo>, query: String): List<ProjectFileInfo> {
        if (query.isEmpty()) return files.sortedBy { it.name.lowercase() }
        val q = query.lowercase()
        return files.sortedWith(
            compareBy(
                { !it.name.lowercase().startsWith(q) },
                { !it.name.lowercase().contains(q) },
                { it.name.length },
                { it.name.lowercase() }
            )
        )
    }

    /** 파일명 후보 정렬: 접두사 우선, 짧은 이름 우선 */
    fun sortNames(names: List<String>, query: String): List<String> {
        val q = query.lowercase()
        return names.sortedWith(
            compareBy(
                { !it.lowercase().startsWith(q) },
                { it.length },
                { it.lowercase() }
            )
        )
    }

    fun relativePath(absolutePath: String, basePath: String): String =
        if (basePath.isNotEmpty() && absolutePath.startsWith(basePath))
            absolutePath.removePrefix(basePath).removePrefix("/")
        else absolutePath

    fun toInfo(name: String, absolutePath: String, extension: String?, basePath: String): ProjectFileInfo {
        val relative = relativePath(absolutePath, basePath)
        return ProjectFileInfo(
            name = name,
            path = relative,
            dir = relative.substringBeforeLast("/", ""),
            ext = extension ?: ""
        )
    }
}
