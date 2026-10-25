package com.kiro.intellij.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AsyncSnapshot<T>(
    initialValue: T,
    private val loader: () -> T,
    private val schedule: (() -> Unit) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    @Volatile
    private var value: T = initialValue
    private val refreshPending = AtomicBoolean(false)

    fun current(): T = value

    fun currentAndRefresh(): T {
        val snapshot = value
        refresh()
        return snapshot
    }

    fun refresh() {
        if (!refreshPending.compareAndSet(false, true)) return
        try {
            schedule {
                try {
                    value = loader()
                } catch (t: Throwable) {
                    onError(t)
                } finally {
                    refreshPending.set(false)
                }
            }
        } catch (t: Throwable) {
            refreshPending.set(false)
            onError(t)
        }
    }
}

/**
 * 내장 HTTP 서버. JCEF webview와 Kotlin 백엔드 간 통신.
 * SSE(Server-Sent Events)로 스트리밍 응답 전달.
 */
class ChatBackendServer(private val project: Project, parentDisposable: Disposable) : Disposable {

    private val log = Logger.getInstance(ChatBackendServer::class.java)
    private val server: HttpServer
    private val sessions = ConcurrentHashMap<String, ChatSession>()
    private val openFilesSnapshot = AsyncSnapshot(
        initialValue = "[]",
        loader = { buildOpenFilesJson() },
        schedule = { task -> ApplicationManager.getApplication().invokeLater(task) },
        onError = { error -> log.debug("Unable to refresh open files snapshot", error) }
    )

    val port: Int

    init {
        Disposer.register(parentDisposable, this)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        port = server.address.port

        server.createContext("/api/send") { exchange -> handleSend(exchange) }
        server.createContext("/api/events") { exchange -> handleEvents(exchange) }
        server.createContext("/api/messages") { exchange -> handleMessages(exchange) }
        server.createContext("/api/stop") { exchange -> handleStop(exchange) }
        server.createContext("/api/new-session") { exchange -> handleNewSession(exchange) }
        server.createContext("/api/open-files") { exchange -> handleOpenFiles(exchange) }
        server.createContext("/api/save-image") { exchange -> handleSaveImage(exchange) }
        server.createContext("/api/set-model") { exchange -> handleSetModel(exchange) }
        server.createContext("/api/models") { exchange -> handleModels(exchange) }
        server.createContext("/api/health") { exchange -> handleHealth(exchange) }
        server.createContext("/api/project-files") { exchange -> handleProjectFiles(exchange) }
        server.createContext("/api/agents") { exchange -> handleAgents(exchange) }
        server.createContext("/api/i18n") { exchange -> handleI18n(exchange) }
        server.createContext("/ui") { exchange -> handleUi(exchange) }

        server.start()
        log.info("Chat backend server started on port $port")
        openFilesSnapshot.refresh()

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

            val request = parseSendRequest(body)
            if (request.sessionId.isBlank()) {
                sendResponse(exchange, 400, "Missing session ID")
                return
            }
            if (request.message.isBlank()) {
                sendResponse(exchange, 400, "Empty message")
                return
            }

            val session = sessions[request.sessionId]
            if (session == null) {
                sendResponse(exchange, 404, "Session not found: ${request.sessionId}")
                return
            }

            session.sendMessage(request.message, request.displayMessage)
            sendResponse(exchange, 200, "ok")
        } catch (e: Exception) {
            log.warn("handleSend error", e)
            sendResponse(exchange, 500, e.message ?: "Internal server error")
        }
    }

    private fun parseSendRequest(body: String): SendRequest {
        if (body.trimStart().startsWith("{")) {
            val json = Json.parseToJsonElement(body).jsonObject
            val sessionId = json["sessionId"]?.jsonPrimitive?.content.orEmpty()
            val message = json["message"]?.jsonPrimitive?.content.orEmpty()
            val displayMessage = json["displayMessage"]?.jsonPrimitive?.content ?: message
            return SendRequest(sessionId, message, displayMessage)
        }

        val parts = body.split("\n", limit = 2)
        val sessionId = parts.getOrNull(0).orEmpty()
        val message = parts.getOrNull(1).orEmpty()
        return SendRequest(sessionId, message, message)
    }

    private data class SendRequest(
        val sessionId: String,
        val message: String,
        val displayMessage: String
    )

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

    private fun handleMessages(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        val query = exchange.requestURI.query.orEmpty()
        val sessionId = query.split("&")
            .firstOrNull { it.startsWith("session=") }
            ?.substringAfter("=")
            .orEmpty()
        val session = sessions[sessionId]
        if (session == null) {
            sendResponse(exchange, 404, "Session not found")
            return
        }

        val json = session.persistedMessages().joinToString(",") { message ->
            "{\"role\":\"${escapeJson(message.role)}\",\"content\":\"${escapeJson(message.content)}\",\"timestamp\":${message.timestamp}}"
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

        exchange.responseHeaders.set("Content-Type", "application/json")
        sendResponse(exchange, 200, openFilesSnapshot.currentAndRefresh())
    }

    /** Must run on the IDE event dispatch thread. */
    private fun buildOpenFilesJson(): String {
        val fem = FileEditorManager.getInstance(project)
        val basePath = project.basePath ?: ""
        val activeFile = fem.selectedTextEditor?.virtualFile?.path ?: ""
        val files = fem.openFiles.map { file ->
            val relativePath = if (basePath.isNotEmpty() && file.path.startsWith(basePath)) {
                file.path.removePrefix(basePath).removePrefix("/")
            } else {
                file.path
            }
            val isActive = file.path == activeFile
            "{\"name\":\"${escapeJson(file.name)}\",\"path\":\"${escapeJson(file.path)}\",\"relativePath\":\"${escapeJson(relativePath)}\",\"active\":$isActive}"
        }
        return "[${files.joinToString(",")}]"
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

    /**
     * 프로젝트 파일 목록 반환 (# 자동완성용)
     */
    private fun handleProjectFiles(exchange: HttpExchange) {
        setCorsHeaders(exchange)
        if (exchange.requestMethod == "OPTIONS") { exchange.sendResponseHeaders(200, -1); return }

        val query = exchange.requestURI.query?.let { q ->
            q.split("&").find { it.startsWith("q=") }?.substringAfter("q=")?.lowercase() ?: ""
        } ?: ""

        val result = StringBuilder()
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val files = mutableListOf<ProjectFileInfo>()
                val basePath = project.basePath ?: ""

                ReadAction.nonBlocking<Unit> {
                    val fileIndex = ProjectFileIndex.getInstance(project)
                    fileIndex.iterateContent { file ->
                        if (!file.isDirectory && file.isValid) {
                            val relativePath = if (basePath.isNotEmpty() && file.path.startsWith(basePath))
                                file.path.removePrefix(basePath).removePrefix("/") else file.path
                            val dir = relativePath.substringBeforeLast("/", "")

                            // 쿼리가 있으면 필터링
                            if (query.isEmpty() ||
                                file.name.lowercase().contains(query) ||
                                relativePath.lowercase().contains(query)) {
                                files.add(ProjectFileInfo(file.name, relativePath, dir, file.extension ?: ""))
                            }
                        }
                        files.size < 500 // 최대 500개까지만
                    }
                }.executeSynchronously()

                // 파일명 매칭 우선, 그 다음 경로 매칭
                val sorted = if (query.isNotEmpty()) {
                    files.sortedWith(compareBy(
                        { !it.name.lowercase().startsWith(query) },
                        { !it.name.lowercase().contains(query) },
                        { it.name.lowercase() }
                    ))
                } else {
                    files.sortedBy { it.name.lowercase() }
                }.take(50)

                val json = sorted.joinToString(",") { f ->
                    "{\"name\":\"${escapeJson(f.name)}\",\"path\":\"${escapeJson(f.path)}\",\"dir\":\"${escapeJson(f.dir)}\",\"ext\":\"${escapeJson(f.ext)}\"}"
                }
                result.append("[$json]")
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

    private data class ProjectFileInfo(val name: String, val path: String, val dir: String, val ext: String)
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
                    "placeholder" to "Enter message... (# file, @ agent, / command)",
                    "openFile" to "Open file",
                    "systemLog" to "System log"
                )
            } else {
                mapOf(
                    "placeholder" to "메시지를 입력하세요... (# 파일, @ 에이전트, / 커맨드)",
                    "openFile" to "열린 파일",
                    "systemLog" to "시스템 로그"
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
