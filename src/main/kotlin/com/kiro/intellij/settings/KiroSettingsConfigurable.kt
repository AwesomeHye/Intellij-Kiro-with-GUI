package com.kiro.intellij.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class KiroSettingsConfigurable : Configurable {

    private var commandField: TextFieldWithBrowseButton? = null
    private var modelCombo: javax.swing.JComboBox<String>? = null
    private var trustAllToolsCheckBox: javax.swing.JCheckBox? = null

    override fun getDisplayName(): String = "Kiro"

    override fun createComponent(): JComponent {
        val settings = KiroSettings.getInstance().state
        commandField = TextFieldWithBrowseButton().apply {
            text = settings.kiroCommand
            addBrowseFolderListener(null,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    .withDescription("Path to kiro-cli executable"))
        }
        // kiro-cli와 동기화된 모델 목록 (캐시/fallback으로 즉시 채우고, 조회가 끝나면 갱신)
        modelCombo = javax.swing.JComboBox(KiroModelProvider.getCached().map { it.id }.toTypedArray()).apply {
            selectItemIgnoreCase(this, settings.defaultModel)
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val models = KiroModelProvider.getModelsBlocking()
            javax.swing.SwingUtilities.invokeLater {
                val combo = modelCombo ?: return@invokeLater
                val selected = combo.selectedItem as? String
                combo.model = javax.swing.DefaultComboBoxModel(models.map { it.id }.toTypedArray())
                selectItemIgnoreCase(combo, selected ?: settings.defaultModel)
            }
        }

        trustAllToolsCheckBox = javax.swing.JCheckBox("Allow all tools without confirmation").apply {
            isSelected = settings.trustAllTools
        }

        return panel {
            row("Kiro command:") { cell(commandField!!) }
            row("Default model:") { cell(modelCombo!!) }
            row("Tool permissions:") { cell(trustAllToolsCheckBox!!) }
        }
    }

    private fun selectItemIgnoreCase(combo: javax.swing.JComboBox<String>, value: String) {
        for (i in 0 until combo.itemCount) {
            if (combo.getItemAt(i).equals(value, ignoreCase = true)) {
                combo.selectedIndex = i
                return
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = KiroSettings.getInstance().state
        return commandField?.text != settings.kiroCommand
                || modelCombo?.selectedItem != settings.defaultModel
                || trustAllToolsCheckBox?.isSelected != settings.trustAllTools
    }

    override fun apply() {
        val settings = KiroSettings.getInstance()
        settings.loadState(settings.state.copy(
            kiroCommand = commandField?.text ?: "kiro-cli",
            defaultModel = modelCombo?.selectedItem as? String ?: "Auto",
            trustAllTools = trustAllToolsCheckBox?.isSelected ?: false
        ))
    }

    override fun reset() {
        val settings = KiroSettings.getInstance().state
        commandField?.text = settings.kiroCommand
        modelCombo?.let { selectItemIgnoreCase(it, settings.defaultModel) }
        trustAllToolsCheckBox?.isSelected = settings.trustAllTools
    }

    override fun disposeUIResources() {
        commandField = null
        modelCombo = null
        trustAllToolsCheckBox = null
    }
}
