package com.kiro.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.APP)
@State(name = "KiroSettings", storages = [Storage("KiroWithGui.xml")])
class KiroSettings : PersistentStateComponent<KiroSettings.State> {

    data class State(
        var kiroCommand: String = "kiro-cli",
        var defaultModel: String = "Auto",
        var language: String = "en", // ko, en
        var kiroConfigDir: String = "", // 빈 문자열이면 기본 경로 (~/.kiro) 사용
        var theme: String = "auto", // auto, light, dark
        var trustAllTools: Boolean = false
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        private val themeListeners = CopyOnWriteArrayList<() -> Unit>()

        fun getInstance(): KiroSettings =
            ApplicationManager.getApplication().getService(KiroSettings::class.java)

        fun onThemeChange(callback: () -> Unit) {
            themeListeners.add(callback)
        }

        fun removeThemeListener(callback: () -> Unit) {
            themeListeners.remove(callback)
        }

        fun notifyThemeChange() {
            themeListeners.forEach { it() }
        }
    }
}
