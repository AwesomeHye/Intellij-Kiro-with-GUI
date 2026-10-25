package com.kiro.intellij.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.kiro.intellij.settings.KiroSettings
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/**
 * 채팅 세션 멀티 탭 컨테이너.
 * 상단에 탭 바 (+ 추가, 시계 기록, × 닫기), 하단에 현재 ChatPanel 표시.
 */
class ChatTabPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
    private val backendServer: ChatBackendServer
) : Disposable {

    private val mainPanel = JPanel(BorderLayout())
    private val tabBar = TabBar()
    private val sessionControls = createSessionControls(
        onNewSession = { addNewTab() },
        onHistory = { showHistoryMenu() }
    )
    private val topBar = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2B, 0x2B))
        add(tabBar, BorderLayout.CENTER)
        add(sessionControls, BorderLayout.EAST)
    }
    private val contentArea = JPanel(BorderLayout())
    private val tabs = mutableListOf<TabEntry>()
    private val historyService = ChatHistoryService.getInstance(project)
    private var activeIndex = -1

    val component: JComponent get() = mainPanel

    init {
        Disposer.register(parentDisposable, this)
        mainPanel.add(topBar, BorderLayout.NORTH)
        mainPanel.add(contentArea, BorderLayout.CENTER)

        val mostRecent = historyService.listConversations().firstOrNull()
        if (mostRecent == null) addNewTab() else openConversation(mostRecent.id)
    }

    fun getActiveChatPanel(): ChatPanel? = tabs.getOrNull(activeIndex)?.panel

    fun addNewTab() {
        val conversation = historyService.createConversation(KiroSettings.getInstance().state.defaultModel)
        addConversationTab(conversation.id)
    }

    private fun openConversation(conversationId: String) {
        val existingIndex = tabs.indexOfFirst { it.panel.sessionId == conversationId }
        if (existingIndex >= 0) {
            selectTab(existingIndex)
            return
        }
        if (historyService.getConversation(conversationId) == null) return
        addConversationTab(conversationId)
    }

    private fun addConversationTab(conversationId: String) {
        val panel = ChatPanel(project, this, backendServer, conversationId) {
            ApplicationManager.getApplication().invokeLater { tabBar.repaint() }
        }
        tabs.add(TabEntry(panel))
        selectTab(tabs.lastIndex)
        tabBar.repaint()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1 || index !in tabs.indices) return
        removeTab(index)
    }

    private fun removeTab(index: Int) {
        if (index !in tabs.indices) return
        val wasActive = index == activeIndex
        val entry = tabs.removeAt(index)
        Disposer.dispose(entry.panel)

        if (tabs.isEmpty()) {
            activeIndex = -1
            contentArea.removeAll()
            addNewTab()
            return
        }

        activeIndex = when {
            wasActive -> index.coerceAtMost(tabs.lastIndex)
            index < activeIndex -> activeIndex - 1
            else -> activeIndex.coerceAtMost(tabs.lastIndex)
        }
        selectTab(activeIndex)
        tabBar.repaint()
    }

    private fun deleteConversation(conversationId: String, title: String) {
        val answer = Messages.showYesNoDialog(
            project,
            "Delete conversation '$title'? This cannot be undone.",
            "Delete Conversation",
            Messages.getQuestionIcon()
        )
        if (answer != Messages.YES) return

        val tabIndex = tabs.indexOfFirst { it.panel.sessionId == conversationId }
        historyService.deleteConversation(conversationId)
        if (tabIndex >= 0) removeTab(tabIndex) else tabBar.repaint()
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        contentArea.removeAll()
        contentArea.add(tabs[index].panel.component, BorderLayout.CENTER)
        contentArea.revalidate()
        contentArea.repaint()
        tabBar.repaint()
    }

    private fun showHistoryMenu() {
        val popup = JPopupMenu()
        val conversations = historyService.listConversations()
        if (conversations.isEmpty()) {
            popup.add(JMenuItem("No saved conversations").apply { isEnabled = false })
        } else {
            conversations.forEach { conversation ->
                val timestamp = HISTORY_TIME_FORMAT.format(
                    Instant.ofEpochMilli(conversation.updatedAt).atZone(ZoneId.systemDefault())
                )
                popup.add(JMenuItem("${conversation.title}  ·  $timestamp").apply {
                    addActionListener { openConversation(conversation.id) }
                })
            }

            popup.addSeparator()
            val deleteMenu = JMenu("Delete conversation")
            conversations.forEach { conversation ->
                deleteMenu.add(JMenuItem(conversation.title).apply {
                    addActionListener { deleteConversation(conversation.id, conversation.title) }
                })
            }
            popup.add(deleteMenu)
        }
        popup.show(sessionControls, 0, sessionControls.height)
    }

    override fun dispose() {
        tabs.forEach { Disposer.dispose(it.panel) }
        tabs.clear()
    }

    private data class TabEntry(val panel: ChatPanel)

    private fun tabTitle(entry: TabEntry): String {
        val title = entry.panel.tabName
        return if (title.length <= MAX_TAB_TITLE_LENGTH) title else title.take(MAX_TAB_TITLE_LENGTH - 1) + "…"
    }

    // --- 커스텀 탭 바 ---
    private inner class TabBar : JPanel() {
        private val tabHeight = 32
        private val tabPadding = 12
        private val closeSize = 14
        private val gap = 2

        init {
            preferredSize = Dimension(0, tabHeight)
            isOpaque = true
            background = JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2B, 0x2B))

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    handleClick(e.x, e.y)
                }
            })
        }

        private fun handleClick(mx: Int, my: Int) {
            var x = gap
            val fm = getFontMetrics(font)

            for (i in tabs.indices) {
                val textWidth = fm.stringWidth(tabTitle(tabs[i]))
                val showClose = tabs.size > 1
                val tabWidth = tabPadding + textWidth + (if (showClose) closeSize + 8 else 0) + tabPadding
                val tabRect = Rectangle(x, 0, tabWidth, tabHeight)

                if (tabRect.contains(mx, my)) {
                    if (showClose) {
                        val closeX = x + tabWidth - tabPadding - closeSize
                        val closeY = (tabHeight - closeSize) / 2
                        if (Rectangle(closeX, closeY, closeSize, closeSize).contains(mx, my)) {
                            closeTab(i)
                            return
                        }
                    }
                    selectTab(i)
                    return
                }
                x += tabWidth + gap
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fm = g2.fontMetrics

            var x = gap
            for (i in tabs.indices) {
                val tab = tabs[i]
                val title = tabTitle(tab)
                val textWidth = fm.stringWidth(title)
                val showClose = tabs.size > 1
                val tabWidth = tabPadding + textWidth + (if (showClose) closeSize + 8 else 0) + tabPadding
                val isActive = i == activeIndex

                g2.color = if (isActive) {
                    JBColor(Color.WHITE, Color(0x3C, 0x3C, 0x3C))
                } else {
                    JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x2B, 0x2B, 0x2B))
                }
                g2.fillRoundRect(x, 2, tabWidth, tabHeight - 2, 8, 8)

                g2.color = if (isActive) {
                    JBColor(Color(0x1E, 0x1E, 0x1E), Color(0xE8, 0xE8, 0xE8))
                } else {
                    JBColor(Color(0x80, 0x80, 0x80), Color(0x80, 0x80, 0x80))
                }
                g2.drawString(title, x + tabPadding, (tabHeight + fm.ascent - fm.descent) / 2)

                if (showClose) {
                    val closeX = x + tabWidth - tabPadding - closeSize
                    val closeY = (tabHeight - closeSize) / 2
                    g2.color = JBColor(Color(0xA0, 0xA0, 0xA0), Color(0x70, 0x70, 0x70))
                    g2.stroke = BasicStroke(1.5f)
                    val margin = 3
                    g2.drawLine(closeX + margin, closeY + margin, closeX + closeSize - margin, closeY + closeSize - margin)
                    g2.drawLine(closeX + closeSize - margin, closeY + margin, closeX + margin, closeY + closeSize - margin)
                }
                x += tabWidth + gap
            }

            g2.dispose()
        }
    }

    companion object {
        private const val MAX_TAB_TITLE_LENGTH = 28
        private val HISTORY_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}


internal const val NEW_SESSION_BUTTON_NAME = "kiro.newSession"
internal const val HISTORY_BUTTON_NAME = "kiro.conversationHistory"

internal fun createSessionControls(
    onNewSession: () -> Unit,
    onHistory: () -> Unit
): JPanel {
    val buttonSize = Dimension(28, 24)
    fun button(text: String, name: String, tooltip: String, action: () -> Unit) =
        javax.swing.JButton(text).apply {
            this.name = name
            toolTipText = tooltip
            isFocusable = false
            margin = java.awt.Insets(0, 0, 0, 0)
            preferredSize = buttonSize
            minimumSize = buttonSize
            maximumSize = buttonSize
            addActionListener { action() }
        }

    return JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 4)).apply {
        isOpaque = true
        background = JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2B, 0x2B))
        preferredSize = Dimension(62, 32)
        minimumSize = preferredSize
        add(button("+", NEW_SESSION_BUTTON_NAME, "New session", onNewSession))
        add(button("◷", HISTORY_BUTTON_NAME, "Conversation history", onHistory))
    }
}
