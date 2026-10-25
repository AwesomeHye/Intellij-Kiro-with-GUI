package com.kiro.intellij.chat

import com.intellij.openapi.project.Project

/**
 * 채팅 세션 관리. CLI 프로세스를 래핑하고 이벤트를 메모리에 버퍼링한다.
 * 프론트엔드는 seq 커서 기반 폴링으로 새 이벤트만 가져간다.
 * (SSE는 Remote Development의 포워딩 계층을 통과하지 못해 폴링으로 대체)
 */
class ChatSession(
    val id: String,
    project: Project,
    private val historyService: ChatHistoryService? = null,
    private val onConversationChanged: (() -> Unit)? = null
) {
    data class SessionEvent(val seq: Long, val event: String, val data: String)

    private val savedConversation = historyService?.getConversation(id)
    private val cliProcess = KiroCliProcess(
        project = project,
        initialSessionId = savedConversation?.cliSessionId?.takeIf { it.isNotBlank() },
        onSessionIdChanged = { cliSessionId ->
            historyService?.updateCliSessionId(id, cliSessionId)
            onConversationChanged?.invoke()
        }
    )
    private val events = ArrayDeque<SessionEvent>()
    private var nextSeq = 1L
    private val lock = Any()
    private val responseLock = Any()
    private var assistantBuffer: StringBuilder? = null
    private var assistantPersisted = false

    var model: String?
        get() = cliProcess.model
        set(value) {
            cliProcess.model = value
            historyService?.updateModel(id, value)
            onConversationChanged?.invoke()
        }

    init {
        cliProcess.model = savedConversation?.model?.takeIf { it.isNotBlank() }
    }

    private fun appendEvent(event: String, data: String) {
        synchronized(lock) {
            events.addLast(SessionEvent(nextSeq++, event, data))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /** seq보다 큰 이벤트를 순서대로 반환 */
    fun eventsAfter(seq: Long): List<SessionEvent> = synchronized(lock) {
        events.filter { it.seq > seq }
    }

    fun persistedMessages(): List<ChatHistoryService.Message> =
        historyService?.getConversation(id)?.messages.orEmpty()

    fun sendMessage(message: String, displayMessage: String = message) {
        historyService?.appendMessage(id, ChatMessage.Role.USER, displayMessage)
        onConversationChanged?.invoke()
        synchronized(responseLock) {
            assistantBuffer = StringBuilder()
            assistantPersisted = false
        }
        appendEvent("start", "")

        cliProcess.sendMessage(
            message = message,
            onChunk = { chunk ->
                synchronized(responseLock) {
                    assistantBuffer?.append(extractAssistantContent(chunk))
                }
                appendEvent("chunk", chunk)
            },
            onDone = {
                persistAssistantResponse()
                appendEvent("done", "")
                onConversationChanged?.invoke()
            },
            onError = { error ->
                persistAssistantResponse()
                historyService?.appendMessage(id, ChatMessage.Role.ERROR, error)
                appendEvent("error", error)
                onConversationChanged?.invoke()
            }
        )
    }

    fun stopGeneration() {
        cliProcess.stop()
        persistAssistantResponse()
        appendEvent("done", "")
        onConversationChanged?.invoke()
    }

    fun resetSession() {
        cliProcess.resetSession()
    }

    private fun persistAssistantResponse() {
        val content = synchronized(responseLock) {
            if (assistantPersisted) return
            assistantPersisted = true
            assistantBuffer?.toString().orEmpty().trim()
        }
        if (content.isNotEmpty()) {
            historyService?.appendMessage(id, ChatMessage.Role.ASSISTANT, content)
        }
    }

    private fun extractAssistantContent(chunk: String): String {
        val lines = chunk.split('\n')
        val contentLines = lines.filterNot { it.startsWith("[SYS]") }
        if (contentLines.all { it.isEmpty() }) return ""
        val content = contentLines.joinToString("\n")
        return if (chunk.endsWith("\n") && !content.endsWith("\n")) "$content\n" else content
    }

    companion object {
        private const val MAX_EVENTS = 5000
    }
}
