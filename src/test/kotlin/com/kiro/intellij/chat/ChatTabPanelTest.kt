package com.kiro.intellij.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton

class ChatTabPanelTest {

    @Test
    fun `session controls have fixed width independent from tabs`() {
        val controls = createSessionControls({}, {})

        assertTrue(controls.preferredSize.width >= 56)
        assertNotNull(findButton(controls, NEW_SESSION_BUTTON_NAME))
        assertNotNull(findButton(controls, HISTORY_BUTTON_NAME))
    }

    @Test
    fun `session control buttons invoke their actions`() {
        var newSessionClicks = 0
        var historyClicks = 0
        val controls = createSessionControls(
            onNewSession = { newSessionClicks++ },
            onHistory = { historyClicks++ }
        )

        findButton(controls, NEW_SESSION_BUTTON_NAME)!!.doClick()
        findButton(controls, HISTORY_BUTTON_NAME)!!.doClick()

        assertEquals(1, newSessionClicks)
        assertEquals(1, historyClicks)
    }

    private fun findButton(controls: java.awt.Container, name: String): JButton? =
        controls.components.filterIsInstance<JButton>().firstOrNull { it.name == name }
}
