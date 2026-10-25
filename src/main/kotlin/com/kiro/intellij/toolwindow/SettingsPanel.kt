package com.kiro.intellij.toolwindow

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.openapi.application.ApplicationManager
import com.kiro.intellij.settings.KiroCliResolver
import com.kiro.intellij.settings.KiroModelProvider
import com.kiro.intellij.settings.KiroSettings
import java.awt.*
import java.io.File
import javax.swing.*

/**
 * 설정 패널 - kiro-cli 경로, 기본 모델, 언어, 테마 등
 * JetBrains UI 가이드라인 준수
 */
class SettingsPanel(private val project: Project) {

    companion object {
        fun resolveEffectiveConfigDir(inputDir: String): String {
            return if (inputDir.isBlank()) {
                System.getProperty("user.home") + "/.kiro"
            } else {
                inputDir
            }
        }

        /**
         * Builds the config dir label text based on whether a custom dir is set.
         * Extracted for testability.
         */
        fun buildConfigDirLabel(configDir: String, currentPathPrefix: String, defaultSuffix: String): String {
            val effectivePath = resolveEffectiveConfigDir(configDir)
            val isCustom = configDir.isNotBlank()
            return if (isCustom) {
                "$currentPathPrefix $effectivePath"
            } else {
                "$currentPathPrefix $effectivePath $defaultSuffix"
            }
        }
    }

    private val panel = JPanel(BorderLayout())

    private val kiroPathField = TextFieldWithBrowseButton()
    private val kiroConfigDirField = TextFieldWithBrowseButton()
    // kiro-cli와 동기화된 모델 목록 (캐시/fallback으로 즉시 채우고, 조회가 끝나면 갱신)
    private val modelComboBox = JComboBox(KiroModelProvider.getCached().map { it.id }.toTypedArray())
    private val languageComboBox = JComboBox(arrayOf(
        "Korean" to "ko",
        "English" to "en"
    ).map { it.first }.toTypedArray())
    private val languageMap = mapOf("Korean" to "ko", "English" to "en")
    private val reverseLanguageMap = mapOf("ko" to "Korean", "en" to "English")

    private val themeComboBox = JComboBox(arrayOf("Auto", "Light", "Dark"))
    private val themeMap = mapOf("Auto" to "auto", "Light" to "light", "Dark" to "dark")
    private val reverseThemeMap = mapOf("auto" to "Auto", "light" to "Light", "dark" to "Dark")
    private val trustAllToolsCheckBox = JCheckBox(KiroMessages["settings.trustAllTools"])

    private lateinit var configDirActivePathLabel: JBLabel

    val component: JComponent get() = panel

    init {
        setupUI()
        loadSettings()
        refreshModelList()
    }

    /** kiro-cli에서 실제 모델 목록을 백그라운드로 조회해 콤보를 갱신 (선택값 유지) */
    private fun refreshModelList() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val models = KiroModelProvider.getModelsBlocking()
            SwingUtilities.invokeLater {
                val selected = modelComboBox.selectedItem as? String
                modelComboBox.model = DefaultComboBoxModel(models.map { it.id }.toTypedArray())
                selectModelItem(selected)
            }
        }
    }

    /** 저장값("Auto" 등 레거시 표기 포함)을 대소문자 무시로 선택 */
    private fun selectModelItem(value: String?) {
        if (value == null) return
        for (i in 0 until modelComboBox.itemCount) {
            if (modelComboBox.getItemAt(i).equals(value, ignoreCase = true)) {
                modelComboBox.selectedIndex = i
                return
            }
        }
    }

    private fun setupUI() {
        panel.border = JBUI.Borders.empty(KiroUI.Spacing.xxlarge)
        panel.background = JBColor.background()

        // 헤더
        val headerLabel = JBLabel(KiroMessages["settings.title"]).apply {
            font = font.deriveFont(Font.BOLD, 18f)
            border = JBUI.Borders.emptyBottom(KiroUI.Spacing.xlarge)
        }

        // kiro-cli 경로 카드
        val pathCard = KiroUI.createCard().apply {
            add(KiroUI.createCardHeader(KiroMessages["settings.cliPath"], KiroUI.Icons.settings), BorderLayout.NORTH)

            val contentPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(KiroUI.Spacing.large)
            }

            kiroPathField.addBrowseFolderListener(
                project,
                FileChooserDescriptorFactory.singleFile()
                    .withTitle("kiro-cli")
                    .withDescription(KiroMessages["settings.cliPathDesc"])
            )

            contentPanel.add(kiroPathField, BorderLayout.CENTER)

            // 실제 경로 표시
            val resolvedPath = resolveKiroCliPath(kiroPathField.text.ifBlank { "kiro-cli" })
            val pathInfoLabel = JBLabel(resolvedPath).apply {
                foreground = if (resolvedPath.startsWith("⚠")) KiroUI.Colors.errorForeground else JBColor.gray
                font = font.deriveFont(11f)
                border = JBUI.Borders.emptyTop(KiroUI.Spacing.medium)
            }
            contentPanel.add(pathInfoLabel, BorderLayout.SOUTH)

            add(contentPanel, BorderLayout.CENTER)
        }

        // kiro 설정 디렉토리 카드
        val configDirCard = KiroUI.createCard().apply {
            add(KiroUI.createCardHeader(KiroMessages["settings.configDir"], KiroUI.Icons.folder), BorderLayout.NORTH)

            val contentPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(KiroUI.Spacing.large)
            }

            kiroConfigDirField.addBrowseFolderListener(
                project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle(KiroMessages["settings.configDir"])
                    .withDescription(KiroMessages["settings.configDirDesc"])
            )
            kiroConfigDirField.alignmentX = Component.LEFT_ALIGNMENT
            contentPanel.add(kiroConfigDirField)
            contentPanel.add(Box.createVerticalStrut(KiroUI.Spacing.medium))

            val hintLabel = JBLabel(KiroMessages["settings.configDirHint"]).apply {
                foreground = JBColor.gray
                font = font.deriveFont(11f)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            contentPanel.add(hintLabel)
            contentPanel.add(Box.createVerticalStrut(KiroUI.Spacing.small))

            configDirActivePathLabel = JBLabel().apply {
                icon = KiroUI.Icons.folder
                iconTextGap = KiroUI.Spacing.small
                font = font.deriveFont(11f)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            updateConfigDirLabel(kiroConfigDirField.text)
            contentPanel.add(configDirActivePathLabel)

            add(contentPanel, BorderLayout.CENTER)
        }

        // 기본 모델 카드
        val modelCard = KiroUI.createCard().apply {
            add(KiroUI.createCardHeader(KiroMessages["settings.defaultModel"]), BorderLayout.NORTH)

            val contentPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(KiroUI.Spacing.large)
            }

            modelComboBox.maximumSize = KiroUI.scaledDimension(300, 30)
            contentPanel.add(modelComboBox, BorderLayout.NORTH)
            contentPanel.add(JBLabel(KiroMessages["settings.defaultModelDesc"]).apply {
                foreground = JBColor.gray
                border = JBUI.Borders.emptyTop(KiroUI.Spacing.medium)
            }, BorderLayout.CENTER)

            add(contentPanel, BorderLayout.CENTER)
        }

        // 언어 설정 카드
        val languageCard = KiroUI.createCard().apply {
            add(KiroUI.createCardHeader(KiroMessages["settings.language"]), BorderLayout.NORTH)

            val contentPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(KiroUI.Spacing.large)
            }

            languageComboBox.maximumSize = KiroUI.scaledDimension(200, 30)
            contentPanel.add(languageComboBox, BorderLayout.NORTH)
            contentPanel.add(JBLabel(KiroMessages["settings.languageDesc"]).apply {
                foreground = JBColor.gray
                border = JBUI.Borders.emptyTop(KiroUI.Spacing.medium)
            }, BorderLayout.CENTER)

            add(contentPanel, BorderLayout.CENTER)
        }

        // 테마 설정 카드
        val themeCard = KiroUI.createCard().apply {
            add(KiroUI.createCardHeader(KiroMessages["settings.theme"]), BorderLayout.NORTH)

            val contentPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(KiroUI.Spacing.large)
            }

            themeComboBox.maximumSize = KiroUI.scaledDimension(200, 30)
            contentPanel.add(themeComboBox, BorderLayout.NORTH)
            contentPanel.add(JBLabel(KiroMessages["settings.themeDesc"]).apply {
                foreground = JBColor.gray
                border = JBUI.Borders.emptyTop(KiroUI.Spacing.medium)
            }, BorderLayout.CENTER)

            add(contentPanel, BorderLayout.CENTER)
        }

        // 도구 승인 카드 — 보안을 위해 기본값은 비활성
        val toolApprovalCard = KiroUI.createCard().apply {
            add(KiroUI.createCardHeader(KiroMessages["settings.trustAllTools"]), BorderLayout.NORTH)

            val contentPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(KiroUI.Spacing.large)
            }
            trustAllToolsCheckBox.isOpaque = false
            contentPanel.add(trustAllToolsCheckBox, BorderLayout.NORTH)
            contentPanel.add(JBLabel(KiroMessages["settings.trustAllToolsDesc"]).apply {
                foreground = JBColor.gray
                border = JBUI.Borders.emptyTop(KiroUI.Spacing.medium)
            }, BorderLayout.CENTER)

            add(contentPanel, BorderLayout.CENTER)
        }

        // 버튼 패널
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, KiroUI.Spacing.medium, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(KiroUI.Spacing.xlarge)
        }

        val saveButton = JButton(KiroMessages["settings.save"]).apply {
            addActionListener { saveSettings() }
        }
        val resetButton = JButton(KiroMessages["settings.reset"]).apply {
            addActionListener { resetSettings() }
        }
        val openConfigButton = JButton(KiroMessages["settings.openConfig"]).apply {
            icon = KiroUI.Icons.folder
            addActionListener { openConfigFile() }
        }

        buttonPanel.add(saveButton)
        buttonPanel.add(resetButton)
        buttonPanel.add(openConfigButton)

        // 조립
        val contentWrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        headerLabel.alignmentX = Component.LEFT_ALIGNMENT
        pathCard.alignmentX = Component.LEFT_ALIGNMENT
        configDirCard.alignmentX = Component.LEFT_ALIGNMENT
        modelCard.alignmentX = Component.LEFT_ALIGNMENT
        languageCard.alignmentX = Component.LEFT_ALIGNMENT
        themeCard.alignmentX = Component.LEFT_ALIGNMENT
        toolApprovalCard.alignmentX = Component.LEFT_ALIGNMENT
        buttonPanel.alignmentX = Component.LEFT_ALIGNMENT

        contentWrapper.add(headerLabel)
        contentWrapper.add(pathCard)
        contentWrapper.add(Box.createVerticalStrut(KiroUI.Spacing.large))
        contentWrapper.add(configDirCard)
        contentWrapper.add(Box.createVerticalStrut(KiroUI.Spacing.large))
        contentWrapper.add(modelCard)
        contentWrapper.add(Box.createVerticalStrut(KiroUI.Spacing.large))
        contentWrapper.add(languageCard)
        contentWrapper.add(Box.createVerticalStrut(KiroUI.Spacing.large))
        contentWrapper.add(themeCard)
        contentWrapper.add(Box.createVerticalStrut(KiroUI.Spacing.large))
        contentWrapper.add(toolApprovalCard)
        contentWrapper.add(buttonPanel)
        contentWrapper.add(Box.createVerticalGlue())

        panel.add(JBScrollPane(contentWrapper).apply {
            border = null
            viewport.isOpaque = false
            isOpaque = false
        }, BorderLayout.CENTER)
    }

    private fun loadSettings() {
        val state = KiroSettings.getInstance().state
        kiroPathField.text = state.kiroCommand
        kiroConfigDirField.text = state.kiroConfigDir
        selectModelItem(state.defaultModel)
        languageComboBox.selectedItem = reverseLanguageMap[state.language] ?: "English"
        themeComboBox.selectedItem = reverseThemeMap[state.theme] ?: "Auto"
        trustAllToolsCheckBox.isSelected = state.trustAllTools
        updateConfigDirLabel(state.kiroConfigDir)
    }

    private fun saveSettings() {
        val settings = KiroSettings.getInstance()
        val oldLanguage = settings.state.language
        val newLanguage = languageMap[languageComboBox.selectedItem as String] ?: "en"
        val languageChanged = oldLanguage != newLanguage

        val oldTheme = settings.state.theme
        val newTheme = themeMap[themeComboBox.selectedItem as String] ?: "auto"
        val themeChanged = oldTheme != newTheme

        settings.state.kiroCommand = kiroPathField.text.ifBlank { "kiro-cli" }
        settings.state.kiroConfigDir = kiroConfigDirField.text
        settings.state.defaultModel = modelComboBox.selectedItem as String
        settings.state.language = newLanguage
        settings.state.theme = newTheme
        settings.state.trustAllTools = trustAllToolsCheckBox.isSelected

        updateConfigDirLabel(settings.state.kiroConfigDir)

        if (themeChanged) {
            KiroSettings.notifyThemeChange()
        }

        if (languageChanged) {
            KiroUI.showInfoDialog(
                panel,
                "Settings saved.\n\nPlease restart IDE to apply language change.",
                "Restart Required"
            )
        } else {
            KiroUI.showInfoDialog(panel, KiroMessages["settings.saved"], KiroMessages["settings.saveComplete"])
        }
    }

    private fun resetSettings() {
        if (KiroUI.showConfirmDialog(panel, KiroMessages["settings.resetConfirm"], KiroMessages["settings.resetTitle"])) {
            val settings = KiroSettings.getInstance()
            settings.loadState(KiroSettings.State())
            loadSettings()
        }
    }

    private fun openConfigFile() {
        val effectiveDir = resolveEffectiveConfigDir(kiroConfigDirField.text)
        val configDir = java.io.File(effectiveDir, "settings")
        if (configDir.exists()) {
            Desktop.getDesktop().open(configDir)
        } else {
            KiroUI.showInfoDialog(panel, "${KiroMessages["settings.configNotFound"]}: ${configDir.absolutePath}", KiroMessages["settings.info"])
        }
    }

    private fun updateConfigDirLabel(configDir: String) {
        val effectivePath = resolveEffectiveConfigDir(configDir)
        val exists = java.io.File(effectivePath).exists()
        val isCustom = configDir.isNotBlank()

        configDirActivePathLabel.text = if (isCustom) {
            "${KiroMessages["settings.configDirCurrent"]} $effectivePath"
        } else {
            "${KiroMessages["settings.configDirCurrent"]} $effectivePath ${KiroMessages["settings.configDirDefault"]}"
        }
        configDirActivePathLabel.foreground = if (exists) {
            KiroUI.Colors.successForeground
        } else {
            KiroUI.Colors.errorForeground
        }
    }

    private fun resolveKiroCliPath(command: String): String {
        // KiroCliResolver로 실제 경로 확인 (Windows 경로는 "/"로 시작하지 않으므로 실행 가능 여부로 판정)
        val resolved = KiroCliResolver.resolve()
        return if (File(resolved).canExecute()) {
            "📍 $resolved"
        } else {
            "⚠️ ${KiroMessages["settings.cliNotFound"]}"
        }
    }
}
