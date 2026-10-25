package com.kiro.intellij.chat

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import java.util.UUID

/**
 * Project-scoped persistent chat history.
 *
 * Runtime streaming events remain in [ChatSession]; this service is the durable
 * source of truth used to rebuild a conversation after a tab or IDE restart.
 */
@Service(Service.Level.PROJECT)
@State(name = "KiroChatHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ChatHistoryService : PersistentStateComponent<ChatHistoryService.State> {

    data class State(
        var conversations: MutableList<Conversation> = mutableListOf()
    )

    data class Conversation(
        var id: String = "",
        var title: String = DEFAULT_TITLE,
        var createdAt: Long = 0L,
        var updatedAt: Long = 0L,
        var model: String = "",
        var cliSessionId: String = "",
        var messages: MutableList<Message> = mutableListOf()
    )

    data class Message(
        var role: String = "",
        var content: String = "",
        var timestamp: Long = 0L
    )

    private val lock = Any()
    private var myState = State()

    override fun getState(): State = synchronized(lock) { myState.deepCopy() }

    override fun loadState(state: State) {
        synchronized(lock) {
            myState = state.deepCopy()
        }
    }

    fun createConversation(model: String): Conversation = synchronized(lock) {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
            model = model
        )
        myState.conversations.add(conversation)
        conversation.deepCopy()
    }

    fun ensureConversation(id: String, title: String = DEFAULT_TITLE, model: String = ""): Conversation = synchronized(lock) {
        val existing = myState.conversations.firstOrNull { it.id == id }
        if (existing != null) return@synchronized existing.deepCopy()

        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = id,
            title = title.ifBlank { DEFAULT_TITLE },
            createdAt = now,
            updatedAt = now,
            model = model
        )
        myState.conversations.add(conversation)
        conversation.deepCopy()
    }

    fun getConversation(id: String): Conversation? = synchronized(lock) {
        myState.conversations.firstOrNull { it.id == id }?.deepCopy()
    }

    fun listConversations(): List<Conversation> = synchronized(lock) {
        myState.conversations
            .sortedByDescending { it.updatedAt }
            .map { it.deepCopy() }
    }

    fun appendMessage(id: String, role: ChatMessage.Role, content: String): Conversation? = synchronized(lock) {
        val normalized = content.trim()
        if (normalized.isEmpty()) return@synchronized getConversationLocked(id)

        val conversation = myState.conversations.firstOrNull { it.id == id } ?: return@synchronized null
        val now = System.currentTimeMillis()
        conversation.messages.add(Message(role.name, normalized, now))
        conversation.updatedAt = now
        if (role == ChatMessage.Role.USER && conversation.title == DEFAULT_TITLE) {
            conversation.title = titleFrom(normalized)
        }
        conversation.deepCopy()
    }

    fun updateModel(id: String, model: String?) {
        synchronized(lock) {
            val conversation = myState.conversations.firstOrNull { it.id == id } ?: return
            conversation.model = model.orEmpty()
            conversation.updatedAt = System.currentTimeMillis()
        }
    }

    fun updateCliSessionId(id: String, cliSessionId: String?) {
        synchronized(lock) {
            val conversation = myState.conversations.firstOrNull { it.id == id } ?: return
            conversation.cliSessionId = cliSessionId.orEmpty()
            conversation.updatedAt = System.currentTimeMillis()
        }
    }

    fun deleteConversation(id: String): Boolean = synchronized(lock) {
        myState.conversations.removeIf { it.id == id }
    }

    fun hasMessages(id: String): Boolean = synchronized(lock) {
        myState.conversations.firstOrNull { it.id == id }?.messages?.isNotEmpty() == true
    }

    private fun getConversationLocked(id: String): Conversation? =
        myState.conversations.firstOrNull { it.id == id }?.deepCopy()

    private fun titleFrom(message: String): String {
        val firstLine = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.isEmpty()) return DEFAULT_TITLE
        return if (firstLine.length <= MAX_TITLE_LENGTH) firstLine else firstLine.take(MAX_TITLE_LENGTH - 1) + "…"
    }

    private fun State.deepCopy(): State = State(conversations.map { it.deepCopy() }.toMutableList())

    private fun Conversation.deepCopy(): Conversation = copy(
        messages = messages.map { it.copy() }.toMutableList()
    )

    companion object {
        const val DEFAULT_TITLE = "New Chat"
        private const val MAX_TITLE_LENGTH = 48

        fun getInstance(project: Project): ChatHistoryService =
            project.getService(ChatHistoryService::class.java)
    }
}
