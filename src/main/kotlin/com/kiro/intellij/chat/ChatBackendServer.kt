package com.kiro.intellij.chat

import com.intellij.history.LocalHistory
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.kiro.intellij.diff.KiroDiffHandler
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 내장 HTTP 서버. JCEF webview와 Kotlin 백엔드 간 통신.
 * SSE(Server-Sent Events)로 스트리밍 응답 전달.
 */
class ChatBackendServer(private val project: Project, parentDisposable: Disposable) : Disposable {

    private val log = Logger.getInstance(ChatBackendServer::class.java)
    private val server: HttpServer
    private val sessions = ConcurrentHashMap<String, ChatSession>()

    val port: Int

    init {
        Disposer.register(parentDisposable, this)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        port = server.address.port

        server.createContext("/api/send") { exchange -> handleSend(exchange) }
        server.createContext("/api/events") { exchange -> handleEvents(exchange) }
        server.createContext("/api/stop") { exchange -> handleStop(exchange) }
        server.createContext("/api/new-session") { exchange -> handleNewSession(exchange) }
        server.createContext("/api/open-files") { exchange -> handleOpenFiles(exchange) }
        server.createContext("/api/save-image") { exchange -> handleSaveImage(exchange) }
        server.createContext("/api/set-model") { exchange -> handleSetModel(exchange) }
        server.createContext("/api/models") { exchange -> handleModels(exchange) }
        server.createContext("/api/health") { exchange -> handleHealth(exchange) }
        server.createContext("/api/project-files") { exchange -> handleProjectFiles(exchange) }
        server.createContext("/api/file-diff") { exchange -> handleFileDiff(exchange) }
        server.createContext("/api/file-revert") { exchange -> handleFileRevert(exchange) }
        server.createContext("/api/project-symbols") { exchange -> handleProjectSymbols(exchange) }
        server.createContext("/api/agents") { exchange -> handleAgents(exchange) }
        server.createContext("/api/i18n") { exchange -> handleI18n(exchange) }
        server.createContext("/ui") { exchange -> handleUi(exchange) }

        server.start()
        log.info("Chat backend server started on port $port")

        // 모델 목록 미리 조회 (~2초 소요) — UI가 요청할 때쯤 캐시가 준비되도록
        com.kiro.intellij.settings.KiroModelProvider.warmUp()
    }

    fun registerSession(sessionId: String, session: ChatSession) {
        sessions[sessionId] = session
    }

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    private fun handleSend(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        try {
            val body = exchange.requestBody.bufferedReader().readText()
            if (body.isBlank()) {
                sendResponse(exchange, 400, "Empty request body")
                return
            }

            val parts = body.split("\n", limit = 2)
            val sessionId = parts.getOrNull(0) ?: ""
            val message = parts.getOrNull(1) ?: ""

            if (sessionId.isBlank()) {
                sendResponse(exchange, 400, "Missing session ID")
                return
            }
            if (message.isBlank()) {
                sendResponse(exchange, 400, "Empty message")
                return
            }

            val session = sessions[sessionId]
            if (session == null) {
                sendResponse(exchange, 404, "Session not found: $sessionId")
                return
            }

            session.sendMessage(message)
            sendResponse(exchange, 200, "ok")
        } catch (e: Exception) {
            log.warn("handleSend error", e)
            sendResponse(exchange, 500, e.message ?: "Internal server error")
        }
    }

    /**
     * 이벤트 폴링 endpoint. 커서(after) 이후에 쌓인 세션 이벤트를 JSON 배열로 반환.
     * SSE와 달리 매 요청이 즉시 종료되므로 Remote Development의 포워딩 계층도 통과한다.
     */
    private fun handleEvents(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        val query = exchange.requestURI.query ?: ""
        val sessionId = query.substringAfter("session=").substringBefore("&")
        val after = query.split("&").find { it.startsWith("after=") }
            ?.substringAfter("=")?.toLongOrNull() ?: 0L

        val session = sessions[sessionId]
        if (session == null) {
            sendResponse(exchange, 404, "Session not found")
            return
        }

        val json = session.eventsAfter(after).joinToString(",") { e ->
            "{\"seq\":${e.seq},\"event\":\"${escapeJson(e.event)}\",\"data\":\"${escapeJson(e.data)}\"}"
        }
        exchange.responseHeaders.set("Content-Type", "application/json")
        sendResponse(exchange, 200, "[$json]")
    }

    private fun handleNewSession(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }
        sendResponse(exchange, 200, "ok")
    }

    private fun handleStop(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }
        try {
            val sessionId = exchange.requestBody.bufferedReader().readText().trim()
            val session = sessions[sessionId]
            if (session == null) {
                sendResponse(exchange, 404, "Session not found: $sessionId")
                return
            }
            session.stopGeneration()
            sendResponse(exchange, 200, "ok")
        } catch (e: Exception) {
            log.warn("handleStop error", e)
            sendResponse(exchange, 500, e.message ?: "Internal server error")
        }
    }

    /**
     * 사용 가능한 모델 목록 반환 (kiro-cli와 동기화, 캐시됨).
     * 이 핸들러는 서버 풀 스레드에서 실행되므로 첫 호출이 CLI 조회를 기다려도 UI를 막지 않는다.
     */
    private fun handleModels(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        val models = com.kiro.intellij.settings.KiroModelProvider.getModelsBlocking()
        val json = models.joinToString(",") { m ->
            "{\"value\":\"${escapeJson(m.id)}\",\"label\":\"${escapeJson(m.label)}\",\"description\":\"${escapeJson(m.description)}\"}"
        }
        exchange.responseHeaders.set("Content-Type", "application/json")
        sendResponse(exchange, 200, "[$json]")
    }

    private fun handleSetModel(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }
        try {
            val body = exchange.requestBody.bufferedReader().readText()
            val parts = body.split("\n", limit = 2)
            val sessionId = parts.getOrNull(0) ?: ""
            val model = parts.getOrNull(1) ?: ""
            val session = sessions[sessionId]
            if (session == null) { sendResponse(exchange, 404, "Session not found: $sessionId"); return }
            session.model = model.ifBlank { null }
            sendResponse(exchange, 200, "ok")
        } catch (e: Exception) {
            log.warn("handleSetModel error", e)
            sendResponse(exchange, 500, e.message ?: "Internal server error")
        }
    }

    private fun handleOpenFiles(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        val result = StringBuilder()
        val latch = CountDownLatch(1)
        ApplicationManager.getApplication().invokeLater {
            try {
                val fem = FileEditorManager.getInstance(project)
                val basePath = project.basePath ?: ""
                val activeFile = fem.selectedTextEditor?.virtualFile?.path ?: ""
                val files = fem.openFiles.map { file ->
                    val relativePath = if (basePath.isNotEmpty() && file.path.startsWith(basePath))
                        file.path.removePrefix(basePath).removePrefix("/") else file.path
                    val isActive = file.path == activeFile
                    "{\"name\":\"${escapeJson(file.name)}\",\"path\":\"${escapeJson(file.path)}\",\"relativePath\":\"${escapeJson(relativePath)}\",\"active\":$isActive}"
                }
                result.append("[${files.joinToString(",")}]")
            } catch (e: Exception) {
                result.append("[]")
            }
            latch.countDown()
        }
        val completed = latch.await(5, TimeUnit.SECONDS)
        if (!completed) {
            log.warn("handleOpenFiles timed out after 5s")
        }
        exchange.responseHeaders.set("Content-Type", "application/json")
        sendResponse(exchange, 200, if (completed) result.toString() else "[]")
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }

    private fun handleSaveImage(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        try {
            val body = exchange.requestBody.bufferedReader().readText()
            // 간단한 JSON 파싱 (name, data 추출)
            val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)
            val dataMatch = Regex("\"data\"\\s*:\\s*\"(data:image/[^\"]+)\"").find(body)
            val name = nameMatch?.groupValues?.get(1) ?: "image.png"
            val dataUrl = dataMatch?.groupValues?.get(1) ?: ""

            if (dataUrl.isBlank()) {
                sendResponse(exchange, 400, "No image data")
                return
            }

            // base64 디코딩 후 임시 파일 저장
            val base64Data = dataUrl.substringAfter(",")
            val bytes = java.util.Base64.getDecoder().decode(base64Data)
            val ext = name.substringAfterLast(".", "png")
            val tmpFile = java.io.File.createTempFile("kiro-img-", ".$ext")
            tmpFile.writeBytes(bytes)
            tmpFile.deleteOnExit()

            sendResponse(exchange, 200, tmpFile.absolutePath)
        } catch (e: Exception) {
            sendResponse(exchange, 500, e.message ?: "error")
        }
    }

    private fun handleHealth(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }
        try {
            val validation = KiroCliValidator.validate()
            val json = buildString {
                append("{")
                append("\"status\":\"ok\",")
                append("\"sessions\":${sessions.size},")
                append("\"cliFound\":${validation.cliFound},")
                append("\"cliPath\":\"${escapeJson(validation.cliPath ?: "")}\",")
                append("\"version\":\"${escapeJson(validation.version ?: "unknown")}\",")
                append("\"authenticated\":${validation.authenticated}")
                append("}")
            }
            exchange.responseHeaders.set("Content-Type", "application/json")
            sendResponse(exchange, 200, json)
        } catch (e: Exception) {
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"${escapeJson(e.message ?: "")}\"}")
        }
    }

    // ---- 에이전트가 쓴 파일: diff 보기 / 되돌리기 --------------------------------
    // CLI는 --trust-all-tools로 이미 파일을 써 버렸으므로 사전 승인은 불가능하다.
    // 대신 IDE Local History에서 "턴 시작 전" 버전을 꺼내 비교하고 되돌린다 (git 불필요).

    /** 요청 본문: "<sessionId>\n<path>" (handleSend와 같은 형식) */
    private fun parseFileRequest(exchange: HttpExchange): Result<Pair<ChatSession, VirtualFile>> {
        val body = exchange.requestBody.bufferedReader().readText()
        val parts = body.split("\n", limit = 2)
        val sessionId = parts.getOrNull(0)?.trim().orEmpty()
        val session = sessions[sessionId]
            ?: return Result.failure(IllegalStateException("session not found: $sessionId"))
        val rawPath = parts.getOrNull(1)?.trim().orEmpty()
        if (rawPath.isEmpty()) return Result.failure(IllegalStateException("missing path"))

        val file = resolveFile(rawPath)
            ?: return Result.failure(IllegalStateException("file not found: $rawPath"))
        return Result.success(session to file)
    }

    /**
     * CLI는 절대 경로와 프로젝트 상대 경로를 섞어 출력한다. VFS는 절대 경로만 받으므로
     * 프로젝트 루트(= CLI의 작업 디렉터리) 기준으로 절대 경로를 만들어 조회한다.
     */
    private fun resolveFile(rawPath: String): VirtualFile? {
        val basePath = project.basePath ?: ""
        val home = System.getProperty("user.home") ?: ""
        val candidate = FileWriteDetector.absoluteCandidate(rawPath, basePath, home)
        val lfs = LocalFileSystem.getInstance()

        lfs.refreshAndFindFileByPath(candidate)?.let { return it }
        // 심볼릭 링크나 './..' 이 섞인 경우
        return try {
            lfs.refreshAndFindFileByPath(File(candidate).canonicalPath)
        } catch (e: Exception) {
            null
        }
    }

    /** 턴이 시작되기 전의 내용. null이면 그 시점에 존재하지 않았던 파일(= 에이전트가 만든 파일). */
    private fun contentBeforeTurn(file: VirtualFile, turnStartMillis: Long): ByteArray? = try {
        LocalHistory.getInstance().getByteContent(file) { revisionTimestamp ->
            revisionTimestamp < turnStartMillis
        }
    } catch (e: Exception) {
        log.warn("local history lookup failed for ${file.path}", e)
        null
    }

    private fun handleFileDiff(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        try {
            val (session, file) = parseFileRequest(exchange).getOrElse { e ->
                exchange.responseHeaders.set("Content-Type", "application/json")
                return sendResponse(exchange, 404, "{\"error\":\"${escapeJson(e.message ?: "not found")}\"}")
            }

            val before = contentBeforeTurn(file, session.turnStartMillis)
            val original = before?.toString(Charsets.UTF_8) ?: ""
            val current = String(file.contentsToByteArray(), Charsets.UTF_8)

            KiroDiffHandler(project).showDiff(file.path, original, current)
            exchange.responseHeaders.set("Content-Type", "application/json")
            sendResponse(exchange, 200, "{\"success\":true,\"created\":${before == null}}")
        } catch (e: Exception) {
            log.warn("handleFileDiff error", e)
            sendResponse(exchange, 500, "{\"error\":\"${escapeJson(e.message ?: "diff failed")}\"}")
        }
    }

    private fun handleFileRevert(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        try {
            val (session, file) = parseFileRequest(exchange).getOrElse { e ->
                exchange.responseHeaders.set("Content-Type", "application/json")
                return sendResponse(exchange, 404, "{\"error\":\"${escapeJson(e.message ?: "not found")}\"}")
            }

            val before = contentBeforeTurn(file, session.turnStartMillis)
            val result = StringBuilder()
            val latch = CountDownLatch(1)

            ApplicationManager.getApplication().invokeLater {
                try {
                    WriteCommandAction.runWriteCommandAction(project, "Revert Kiro change", null, {
                        if (before == null) {
                            // 에이전트가 만든 파일 → 되돌리기 = 삭제
                            file.delete(this)
                            result.append("{\"success\":true,\"deleted\":true}")
                        } else {
                            file.setBinaryContent(before)
                            result.append("{\"success\":true,\"deleted\":false}")
                        }
                    })
                } catch (e: Exception) {
                    log.warn("revert failed for ${file.path}", e)
                    result.append("{\"error\":\"${escapeJson(e.message ?: "revert failed")}\"}")
                }
                latch.countDown()
            }

            val completed = latch.await(10, TimeUnit.SECONDS)
            exchange.responseHeaders.set("Content-Type", "application/json")
            sendResponse(exchange, 200, if (completed) result.toString() else "{\"error\":\"timeout\"}")
        } catch (e: Exception) {
            log.warn("handleFileRevert error", e)
            sendResponse(exchange, 500, "{\"error\":\"${escapeJson(e.message ?: "revert failed")}\"}")
        }
    }

    /**
     * 프로젝트 파일 목록 반환 (@ 자동완성용).
     *
     * 파일명 쿼리는 IDE의 이름 인덱스(FilenameIndex)로 바로 찾는다. 예전 구현은 프로젝트 전체를
     * iterateContent로 훑고 메모리에서 걸렀는데, 모노레포에서는 느리고 500개에서 잘려
     * 찾는 파일이 아예 안 나올 수 있었다.
     *
     * 인덱스를 쓸 수 없는 세 경우만 전체 순회로 내려간다:
     *   - 쿼리가 비었을 때 (열린 파일을 먼저 보여준다)
     *   - 쿼리에 '/'가 있을 때 (경로 검색이라 이름 인덱스로는 못 찾는다)
     *   - 인덱싱 중(DumbMode)
     */
    private fun handleProjectFiles(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        val query = exchange.requestURI.query?.let { q ->
            q.split("&").find { it.startsWith("q=") }?.substringAfter("q=")
        }?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""

        val result = StringBuilder()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val files = ReadAction.nonBlocking<List<ProjectFileInfo>> {
                    collectProjectFiles(query)
                }.executeSynchronously()

                val json = files.joinToString(",") { f ->
                    "{\"name\":\"${escapeJson(f.name)}\",\"path\":\"${escapeJson(f.path)}\"," +
                        "\"dir\":\"${escapeJson(f.dir)}\",\"ext\":\"${escapeJson(f.ext)}\"}"
                }
                result.append("[$json]")
            } catch (e: IndexNotReadyException) {
                result.append("[]")
            } catch (e: Exception) {
                log.warn("handleProjectFiles error", e)
                result.append("[]")
            }
            latch.countDown()
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) {
            log.warn("handleProjectFiles timed out after 10s")
        }
        exchange.responseHeaders.set("Content-Type", "application/json")
        sendResponse(exchange, 200, if (completed) result.toString() else "[]")
    }

    /** ReadAction 안에서만 호출해야 한다. */
    private fun collectProjectFiles(query: String): List<ProjectFileInfo> {
        val basePath = project.basePath ?: ""
        val useNameIndex = query.isNotEmpty() &&
            !ProjectFileSearch.isPathQuery(query) &&
            !DumbService.isDumb(project)

        val files = if (useNameIndex) {
            filesFromNameIndex(query, basePath)
        } else {
            filesFromContentScan(query, basePath)
        }

        return ProjectFileSearch.sort(files, query).take(ProjectFileSearch.RESULT_LIMIT)
    }

    /** IDE 이름 인덱스로 파일명을 찾는다: 전체 순회 없이 후보만 해석한다. */
    private fun filesFromNameIndex(query: String, basePath: String): List<ProjectFileInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val fileIndex = ProjectFileIndex.getInstance(project)
        val lower = query.lowercase()
        val names = mutableListOf<String>()

        FilenameIndex.processAllFileNames({ name ->
            if (name.lowercase().contains(lower)) names.add(name)
            names.size < ProjectFileSearch.NAME_CANDIDATE_LIMIT
        }, scope, null)

        val out = mutableListOf<ProjectFileInfo>()
        for (name in ProjectFileSearch.sortNames(names, query)) {
            for (file in FilenameIndex.getVirtualFilesByName(name, scope)) {
                if (!isMentionableFile(file, fileIndex)) continue
                out.add(ProjectFileSearch.toInfo(file.name, file.path, file.extension, basePath))
                if (out.size >= ProjectFileSearch.RESULT_LIMIT) return out
            }
        }
        return out
    }

    /**
     * 목록에 넣을 파일인지 판단. 컴파일 산출물(.class 등)과 바이너리를 빼고,
     * 빌드 출력 폴더나 라이브러리 클래스에 있는 파일도 제외한다
     * (프로젝트 설정에 따라 인덱스가 이런 파일까지 들고 있다).
     */
    private fun isMentionableFile(file: VirtualFile, fileIndex: ProjectFileIndex): Boolean {
        if (file.isDirectory || !file.isValid) return false
        if (!ProjectFileSearch.isMentionable(file.extension, file.fileType.isBinary)) return false
        return try {
            !fileIndex.isExcluded(file) && !fileIndex.isInLibraryClasses(file)
        } catch (e: Exception) {
            true // 판단 불가면 보여준다: 확장자 필터는 이미 통과했다
        }
    }

    /**
     * 인덱스를 못 쓸 때의 대안. 빈 쿼리라면 열린 파일을 먼저 넣어
     * '@'만 눌렀을 때 지금 보고 있는 파일이 위에 오게 한다.
     */
    private fun filesFromContentScan(query: String, basePath: String): List<ProjectFileInfo> {
        val out = LinkedHashMap<String, ProjectFileInfo>()
        val fileIndex = ProjectFileIndex.getInstance(project)

        if (query.isEmpty()) {
            FileEditorManager.getInstance(project).openFiles.forEach { file ->
                if (isMentionableFile(file, fileIndex)) {
                    val info = ProjectFileSearch.toInfo(file.name, file.path, file.extension, basePath)
                    out[info.path] = info
                }
            }
        }

        var scanned = 0
        fileIndex.iterateContent { file ->
            if (isMentionableFile(file, fileIndex)) {
                scanned++
                val info = ProjectFileSearch.toInfo(file.name, file.path, file.extension, basePath)
                if (ProjectFileSearch.matches(info, query)) out.putIfAbsent(info.path, info)
            }
            out.size < ProjectFileSearch.SCAN_LIMIT && scanned < ProjectFileSearch.SCAN_LIMIT
        }
        return out.values.toList()
    }

    /**
     * 클래스/심볼 목록 반환 (@ 자동완성용).
     * IDE 인덱스(ChooseByNameContributor)를 그대로 쓰므로 언어별 구현을 따로 만들 필요가 없다.
     * 인덱싱 중(DumbMode)이거나 쿼리가 2자 미만이면 빈 배열을 돌려준다.
     */
    private fun handleProjectSymbols(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        val query = exchange.requestURI.query?.let { q ->
            q.split("&").find { it.startsWith("q=") }?.substringAfter("q=") ?: ""
        }?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""

        if (query.length < MIN_SYMBOL_QUERY || DumbService.isDumb(project)) {
            exchange.responseHeaders.set("Content-Type", "application/json")
            sendResponse(exchange, 200, "[]")
            return
        }

        val result = StringBuilder()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val symbols = mutableListOf<ProjectSymbolInfo>()
                ReadAction.nonBlocking<Unit> {
                    collectSymbols(query, ChooseByNameContributor.CLASS_EP_NAME.extensionList, "class", symbols)
                    if (symbols.size < SYMBOL_LIMIT) {
                        collectSymbols(query, ChooseByNameContributor.SYMBOL_EP_NAME.extensionList, "symbol", symbols)
                    }
                }.executeSynchronously()

                val lower = query.lowercase()
                val sorted = symbols
                    .distinctBy { it.name + "|" + it.path }
                    .sortedWith(compareBy(
                        { !it.name.lowercase().startsWith(lower) },
                        { it.name.length },
                        { it.name.lowercase() }
                    ))
                    .take(SYMBOL_LIMIT)

                val json = sorted.joinToString(",") { s ->
                    "{\"name\":\"${escapeJson(s.name)}\",\"path\":\"${escapeJson(s.path)}\"," +
                        "\"kind\":\"${escapeJson(s.kind)}\",\"location\":\"${escapeJson(s.location)}\"}"
                }
                result.append("[$json]")
            } catch (e: IndexNotReadyException) {
                result.append("[]")
            } catch (e: Exception) {
                log.warn("handleProjectSymbols error", e)
                result.append("[]")
            }
            latch.countDown()
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) {
            log.warn("handleProjectSymbols timed out after 10s")
        }
        exchange.responseHeaders.set("Content-Type", "application/json")
        sendResponse(exchange, 200, if (completed) result.toString() else "[]")
    }

    /** ReadAction 안에서만 호출해야 한다. */
    private fun collectSymbols(
        query: String,
        contributors: List<ChooseByNameContributor>,
        kind: String,
        out: MutableList<ProjectSymbolInfo>
    ) {
        val lower = query.lowercase()
        val basePath = project.basePath ?: ""
        for (contributor in contributors) {
            if (out.size >= SYMBOL_LIMIT) return
            val names = try {
                contributor.getNames(project, false)
            } catch (e: Exception) {
                continue
            }
            val matched = names.asSequence()
                .filter { it.lowercase().contains(lower) }
                .sortedBy { if (it.lowercase().startsWith(lower)) 0 else 1 }
                .take(SYMBOL_LIMIT)
            for (name in matched) {
                if (out.size >= SYMBOL_LIMIT) return
                val items = try {
                    contributor.getItemsByName(name, query, project, false)
                } catch (e: Exception) {
                    continue
                }
                for (item in items) {
                    if (out.size >= SYMBOL_LIMIT) return
                    val vFile = (item as? PsiElement)?.containingFile?.virtualFile ?: continue
                    // 라이브러리에서 디컴파일된 .class 심볼은 멘션해도 CLI가 읽을 수 없다
                    if (!ProjectFileSearch.isMentionable(vFile.extension, vFile.fileType.isBinary)) continue
                    val relative = if (basePath.isNotEmpty() && vFile.path.startsWith(basePath))
                        vFile.path.removePrefix(basePath).removePrefix("/") else vFile.path
                    val presentation = item.presentation
                    out.add(
                        ProjectSymbolInfo(
                            name = presentation?.presentableText ?: name,
                            path = relative,
                            kind = kind,
                            location = presentation?.locationString ?: relative.substringBeforeLast("/", "")
                        )
                    )
                }
            }
        }
    }

    /**
     * 에이전트 목록 반환 (@ 자동완성용)
     */
    private fun handleAgents(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        try {
            val agents = mutableListOf<AgentInfo>()

            // 프로젝트 레벨 에이전트 (.kiro/agents/)
            val projectAgentsDir = project.basePath?.let { File(it, ".kiro/agents") }
            if (projectAgentsDir?.exists() == true) {
                projectAgentsDir.listFiles { f -> f.extension == "md" }?.forEach { file ->
                    parseAgentFile(file)?.let { agents.add(it) }
                }
            }

            // 사용자 레벨 에이전트 (~/.kiro/agents/)
            val userAgentsDir = File(System.getProperty("user.home"), ".kiro/agents")
            if (userAgentsDir.exists()) {
                userAgentsDir.listFiles { f -> f.extension == "md" }?.forEach { file ->
                    // 프로젝트 레벨에 같은 이름이 없으면 추가
                    val agentInfo = parseAgentFile(file)
                    if (agentInfo != null && agents.none { it.name == agentInfo.name }) {
                        agents.add(agentInfo)
                    }
                }
            }

            val json = agents.sortedBy { it.name }.joinToString(",") { a ->
                "{\"name\":\"${escapeJson(a.name)}\",\"description\":\"${escapeJson(a.description)}\",\"path\":\"${escapeJson(a.path)}\"}"
            }
            exchange.responseHeaders.set("Content-Type", "application/json")
            sendResponse(exchange, 200, "[$json]")
        } catch (e: Exception) {
            log.warn("handleAgents error", e)
            sendResponse(exchange, 500, "[]")
        }
    }

    /**
     * 에이전트 파일 파싱 (frontmatter에서 name, description 추출)
     */
    private fun parseAgentFile(file: File): AgentInfo? {
        return try {
            val content = file.readText()
            val name = file.nameWithoutExtension

            // frontmatter 파싱 (---로 감싸진 YAML)
            val frontmatterMatch = Regex("^---\\s*\\n([\\s\\S]*?)\\n---").find(content)
            val description = if (frontmatterMatch != null) {
                val yaml = frontmatterMatch.groupValues[1]
                Regex("description:\\s*[\"']?(.+?)[\"']?\\s*$", RegexOption.MULTILINE)
                    .find(yaml)?.groupValues?.get(1)?.trim() ?: ""
            } else {
                // frontmatter가 없으면 첫 번째 줄을 설명으로 사용
                content.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(100) ?: ""
            }

            AgentInfo(name, description, file.absolutePath)
        } catch (e: Exception) {
            log.warn("Failed to parse agent file: ${file.name}", e)
            null
        }
    }

    private data class ProjectSymbolInfo(
        val name: String,
        val path: String,
        val kind: String,
        val location: String
    )

    private companion object {
        /** 쿼리가 너무 짧으면 프로젝트 전체 인덱스를 훑게 되므로 막는다 */
        const val MIN_SYMBOL_QUERY = 2
        const val SYMBOL_LIMIT = 30
    }
    private data class AgentInfo(val name: String, val description: String, val path: String)

    // 세션별 HTML 저장 (UI 서빙용)
    private val sessionHtmlMap = ConcurrentHashMap<String, String>()

    fun setSessionHtml(sessionId: String, html: String) {
        sessionHtmlMap[sessionId] = html
    }

    fun removeSessionHtml(sessionId: String) {
        sessionHtmlMap.remove(sessionId)
    }

    /**
     * 세션별 채팅 UI HTML 서빙. JCEF에서 same-origin fetch를 위해 사용.
     */
    private fun handleUi(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        val sessionId = exchange.requestURI.query?.substringAfter("session=")?.substringBefore("&") ?: ""
        val html = sessionHtmlMap[sessionId]
        if (html == null) {
            sendResponse(exchange, 404, "Session not found")
            return
        }
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        sendResponse(exchange, 200, html)
    }

    /**
     * 다국어 메시지 반환 (채팅 UI용)
     */
    private fun handleI18n(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        try {
            val lang = com.kiro.intellij.settings.KiroSettings.getInstance().state.language
            val messages = if (lang == "en") {
                mapOf(
                    "placeholder" to "Enter message... (@ file/class/agent, / command)",
                    "openFile" to "Open file",
                    "systemLog" to "System log",
                    "activity" to "Activity",
                    "thinking" to "Working",
                    "files" to "Files",
                    "classes" to "Classes & symbols",
                    "agents" to "Agents",
                    "filesChanged" to "Files changed",
                    "viewDiff" to "Diff",
                    "revert" to "Revert",
                    "reverted" to "reverted",
                    "deleted" to "deleted"
                )
            } else {
                mapOf(
                    "placeholder" to "메시지를 입력하세요... (@ 파일/클래스/에이전트, / 커맨드)",
                    "openFile" to "열린 파일",
                    "systemLog" to "시스템 로그",
                    "activity" to "작업 내역",
                    "thinking" to "작업 중",
                    "files" to "파일",
                    "classes" to "클래스 · 심볼",
                    "agents" to "에이전트",
                    "filesChanged" to "변경된 파일",
                    "viewDiff" to "변경 내용",
                    "revert" to "되돌리기",
                    "reverted" to "되돌림",
                    "deleted" to "삭제됨"
                )
            }
            val json = messages.entries.joinToString(",") { (k, v) ->
                "\"$k\":\"${escapeJson(v)}\""
            }
            exchange.responseHeaders.set("Content-Type", "application/json")
            sendResponse(exchange, 200, "{$json}")
        } catch (e: Exception) {
            log.warn("handleI18n error", e)
            sendResponse(exchange, 500, "{}")
        }
    }

    fun getSessionCount(): Int = sessions.size

    private fun setCorsHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type")
    }

    private fun sendResponse(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }

    override fun dispose() {
        sessions.clear()
        server.stop(1)
        log.info("Chat backend server stopped (port $port, project: ${project.name})")
    }
}
