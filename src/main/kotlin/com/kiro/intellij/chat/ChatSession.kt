package com.kiro.intellij.chat

import com.intellij.openapi.project.Project

/**
 * 하나의 채팅 세션. KiroCliProcess를 관리하고 응답을 이벤트 버퍼에 적재한다.
 * webview는 /api/events 폴링으로 커서(seq) 이후의 이벤트를 가져간다.
 * (SSE는 Remote Development의 포워딩 계층을 통과하지 못해 폴링으로 대체)
 */
class ChatSession(
    val id: String,
    private val project: Project
) {
    data class SessionEvent(val seq: Long, val event: String, val data: String)

    private val cliProcess = KiroCliProcess(project)
    private val events = ArrayDeque<SessionEvent>()
    private var nextSeq = 1L
    private val lock = Any()

    /**
     * 이 턴이 시작된 시각. 파일을 되돌릴 때 "에이전트가 건드리기 전" 버전을 찾는 기준이 된다
     * (IDE Local History에서 이 시각보다 앞선 마지막 리비전).
     */
    @Volatile
    var turnStartMillis: Long = System.currentTimeMillis()
        private set

    /** 이 턴에 에이전트가 쓴 파일 경로 (중복 없이, 나온 순서대로) */
    private val writtenFiles = LinkedHashSet<String>()

    fun writtenFilesInTurn(): List<String> = synchronized(lock) { writtenFiles.toList() }

    var model: String?
        get() = cliProcess.model
        set(value) { cliProcess.model = value }

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

    fun sendMessage(message: String) {
        synchronized(lock) {
            turnStartMillis = System.currentTimeMillis()
            writtenFiles.clear()
        }
        appendEvent("start", "")

        cliProcess.sendMessage(
            message = message,
            onChunk = { chunk ->
                appendEvent("chunk", chunk)
                detectFileWrite(chunk)
            },
            onDone = {
                appendEvent("done", "")
            },
            onError = { error ->
                appendEvent("error", error)
            }
        )
    }

    /**
     * 활동 로그([SYS]) 줄에서 쓰기 대상 파일을 찾아 별도 이벤트로 알린다.
     * webview는 이 이벤트로 "diff 보기 / 되돌리기" 줄을 그린다.
     */
    private fun detectFileWrite(chunk: String) {
        if (!chunk.startsWith(SYS_PREFIX)) return
        val path = FileWriteDetector.extractPath(chunk.removePrefix(SYS_PREFIX)) ?: return
        val isNew = synchronized(lock) { writtenFiles.add(path) }
        if (isNew) appendEvent("file", path)
    }

    fun stopGeneration() {
        cliProcess.stop()
        appendEvent("done", "")
    }

    fun resetSession() {
        cliProcess.resetSession()
    }

    companion object {
        private const val MAX_EVENTS = 5000
        private const val SYS_PREFIX = "[SYS]"
    }
}
