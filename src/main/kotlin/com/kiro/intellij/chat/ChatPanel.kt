package com.kiro.intellij.chat

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.kiro.intellij.settings.KiroSettings
import java.awt.BorderLayout
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JPanel

class ChatPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
    private val backendServer: ChatBackendServer,
    val tabName: String
) : Disposable {

    private val mainPanel = JPanel(BorderLayout())
    private var browser: JBCefBrowser? = null
    val sessionId = UUID.randomUUID().toString()
    private val session = ChatSession(sessionId, project)

    val component: JComponent get() = mainPanel

    init {
        Disposer.register(parentDisposable, this)
        session.model = KiroSettings.getInstance().state.defaultModel
        backendServer.registerSession(sessionId, session)
        initBrowser()
    }

    private fun resolveInitialBodyClass(): String {
        val theme = KiroSettings.getInstance().state.theme
        return when (theme) {
            "light" -> "theme-light"
            "dark" -> "theme-dark"
            else -> if (!JBColor.isBright()) "theme-dark" else "theme-light"
        }
    }

    private fun initBrowser() {
        val initialBodyClass = resolveInitialBodyClass()
        browser = JBCefBrowser().also { b ->
            Disposer.register(this, b)
            val html = buildHtml(backendServer.port, sessionId, initialBodyClass)
            backendServer.setSessionHtml(sessionId, html)
            b.loadURL("http://127.0.0.1:${backendServer.port}/ui?session=$sessionId")
            mainPanel.add(b.component, BorderLayout.CENTER)
        }

        // IDE 테마 변경 감지 (Auto 모드일 때만 반영)
        val lafListener = LafManagerListener {
            if (KiroSettings.getInstance().state.theme == "auto") {
                val newClass = if (!JBColor.isBright()) "dark" else "light"
                browser?.cefBrowser?.executeJavaScript("setTheme('$newClass')", "", 0)
            }
        }
        // LafManager.add/removeLafManagerListener는 2026.2(build 262)에서 제거됨 → 메시지 버스 구독 사용
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, lafListener)

        // Settings에서 테마 변경 시 즉시 반영
        val themeChangeCallback: () -> Unit = {
            val newBodyClass = resolveInitialBodyClass()
            val newThemeName = if (newBodyClass == "theme-dark") "dark" else "light"
            browser?.cefBrowser?.executeJavaScript("setTheme('$newThemeName')", "", 0)
        }
        KiroSettings.onThemeChange(themeChangeCallback)
        Disposer.register(this) {
            KiroSettings.removeThemeListener(themeChangeCallback)
        }
    }

    fun sendToChat(text: String) {
        val escaped = text.replace("\\", "\\\\").replace("`", "\\`").replace("\n", "\\n")
        browser?.cefBrowser?.executeJavaScript("setInput(`$escaped`)", "", 0)
    }

    fun changeModel(model: String) {
        session.model = model
    }

    override fun dispose() {
        backendServer.removeSession(sessionId)
        backendServer.removeSessionHtml(sessionId)
    }

    internal fun buildHtml(port: Int, sessionId: String, initialBodyClass: String = "theme-dark"): String =
        buildChatHtml(port, sessionId, initialBodyClass)
}

internal fun buildChatHtml(port: Int, sessionId: String, initialBodyClass: String = "theme-dark"): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }

/* Dark theme variables */
body.theme-dark {
    --bg: #1e1e1e;
    --fg: #d4d4d4;
    --fg-muted: #666;
    --fg-faint: #777;
    --pre-bg: #0d1117;
    --pre-border: #30363d;
    --code-copy-bg: rgba(255,255,255,0.1);
    --code-copy-fg: #888;
    --code-inline-bg: #343942;
    --blockquote-border: #555;
    --blockquote-fg: #999;
    --table-border: #444;
    --table-bg: #333;
    --user-bg: #264f78;
    --user-fg: #e8e8e8;
    --assistant-bg: #2d2d2d;
    --assistant-fg: #d4d4d4;
    --accent: #007acc;
    --accent-hover: #005a9e;
    --accent-shadow: rgba(0,122,204,0.3);
    --copy-btn-bg: rgba(255,255,255,0.08);
    --copy-btn-fg: #888;
    --copy-btn-hover-bg: rgba(255,255,255,0.15);
    --preview-bg: #252526;
    --preview-border: #444;
    --danger: #c00;
    --danger-fg: #f44;
    --stop-bg: #6e6e6e;
    --input-bg: #2d2d2d;
    --border: #3c3c3c;
    --placeholder-fg: #6e6e6e;
    --menu-bg: #2d2d2d;
    --menu-hover-bg: #094771;
    --menu-border: #555;
    --icon-btn-fg: #888;
    --icon-btn-hover-fg: #d4d4d4;
    --icon-btn-hover-bg: rgba(255,255,255,0.08);
    --badge-file-bg: rgba(38,79,120,0.4);
    --badge-file-fg: #7cb7e8;
    --badge-file-border: rgba(58,110,165,0.5);
    --badge-agent-bg: rgba(220,220,170,0.2);
    --badge-agent-fg: #dcdcaa;
    --badge-agent-border: rgba(220,220,170,0.4);
}

/* Light theme variables */
body.theme-light {
    --bg: #ffffff;
    --fg: #1f1f1f;
    --fg-muted: #666;
    --fg-faint: #888;
    --pre-bg: #f6f8fa;
    --pre-border: #d0d7de;
    --code-copy-bg: rgba(0,0,0,0.06);
    --code-copy-fg: #57606a;
    --code-inline-bg: #f0f2f4;
    --blockquote-border: #d0d7de;
    --blockquote-fg: #57606a;
    --table-border: #d0d7de;
    --table-bg: #f6f8fa;
    --user-bg: #dbeafe;
    --user-fg: #1e3a8a;
    --assistant-bg: #f6f8fa;
    --assistant-fg: #1f1f1f;
    --accent: #0969da;
    --accent-hover: #0860c9;
    --accent-shadow: rgba(9,105,218,0.3);
    --copy-btn-bg: rgba(0,0,0,0.06);
    --copy-btn-fg: #57606a;
    --copy-btn-hover-bg: rgba(0,0,0,0.12);
    --preview-bg: #f0f2f4;
    --preview-border: #d0d7de;
    --danger: #cf222e;
    --danger-fg: #cf222e;
    --stop-bg: #8c959f;
    --input-bg: #f6f8fa;
    --border: #d0d7de;
    --placeholder-fg: #8c959f;
    --menu-bg: #ffffff;
    --menu-hover-bg: #ddf4ff;
    --menu-border: #d0d7de;
    --icon-btn-fg: #57606a;
    --icon-btn-hover-fg: #1f1f1f;
    --icon-btn-hover-bg: rgba(0,0,0,0.06);
    --badge-file-bg: rgba(219,234,254,0.8);
    --badge-file-fg: #1e40af;
    --badge-file-border: rgba(147,197,253,0.6);
    --badge-agent-bg: rgba(253,246,230,0.8);
    --badge-agent-fg: #92400e;
    --badge-agent-border: rgba(253,211,77,0.5);
}

body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    background: var(--bg); color: var(--fg);
    display: flex; flex-direction: column; height: 100vh;
}
#messages {
    flex: 1; overflow-y: auto; padding: 8px;
    display: flex; flex-direction: column; gap: 4px;
}
.msg {
    padding: 10px 14px; border-radius: 12px;
    font-size: 13px; line-height: 1.6; word-wrap: break-word;
}
.msg pre {
    background: var(--pre-bg); padding: 10px; border-radius: 6px;
    overflow-x: auto; margin: 6px 0; border: 1px solid var(--pre-border);
    white-space: pre; position: relative;
}
.msg pre .code-copy {
    position: absolute; top: 4px; right: 4px;
    background: var(--code-copy-bg); border: none; color: var(--code-copy-fg);
    cursor: pointer; font-size: 11px; padding: 2px 6px;
    border-radius: 4px; opacity: 0; transition: opacity 0.15s;
}
.msg pre:hover .code-copy { opacity: 1; }
.msg pre .code-copy:hover { color: var(--fg); background: var(--copy-btn-hover-bg); }
.msg code { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
.msg :not(pre) > code { background: var(--code-inline-bg); padding: 2px 5px; border-radius: 3px; }
.msg p { margin: 4px 0; }
.msg ul, .msg ol { padding-left: 20px; margin: 4px 0; }
.msg h1,.msg h2,.msg h3,.msg h4 { font-size: 13px; font-weight: bold; margin: 8px 0 4px; }
.msg strong { font-weight: normal; }
.msg blockquote { border-left: 3px solid var(--blockquote-border); padding-left: 10px; color: var(--blockquote-fg); margin: 4px 0; }
.msg hr { display: none; }
.msg table { border-collapse: collapse; margin: 6px 0; width: 100%; display: table; }
.msg th,.msg td { border: 1px solid var(--table-border); padding: 6px 10px; font-size: 12px; text-align: left; }
.msg th { background: var(--table-bg); font-weight: 600; }
.msg tr:nth-child(even) { background: rgba(128,128,128,0.05); }
.user { align-self: flex-end; background: var(--user-bg); color: var(--user-fg); border-bottom-right-radius: 4px; }
.assistant { align-self: flex-start; background: var(--assistant-bg); color: var(--assistant-fg); border-bottom-left-radius: 4px; }
.assistant.streaming { border-left: 2px solid var(--accent); }
.msg-meta { font-size: 11px; color: var(--fg-muted); padding: 2px 8px; align-self: flex-start; }
.msg-wrap { position: relative; max-width: 95%; align-self: flex-start; }
.msg-wrap.user-wrap { align-self: flex-end; }
.msg-wrap .copy-btn {
    position: absolute; top: 4px; right: 4px; background: var(--copy-btn-bg);
    border: none; color: var(--copy-btn-fg); cursor: pointer; font-size: 12px; padding: 2px 5px;
    border-radius: 4px; opacity: 0; transition: opacity 0.15s;
}
.msg-wrap:hover .copy-btn { opacity: 1; }
.msg-wrap .copy-btn:hover { color: var(--fg); background: var(--copy-btn-hover-bg); }
.sys-toggle { align-self: flex-start; max-width: 95%; margin: 2px 0; width: 95%; }
.sys-toggle summary {
    font-size: 11px; color: var(--fg-muted); cursor: pointer; padding: 2px 8px;
    list-style: none; user-select: none;
}
.sys-toggle summary::-webkit-details-marker { display: none; }
.sys-toggle summary::before { content: '▶ '; font-size: 9px; }
.sys-toggle[open] summary::before { content: '▼ '; font-size: 9px; }
/* activity trace (live agent steps) */
.sys-toggle.activity { border-left: 2px solid var(--border); padding-left: 2px; }
.sys-toggle.activity[data-live="1"] { border-left-color: var(--accent); }
.sys-toggle.activity summary .act-summary-label { font-weight: 600; }
.sys-toggle.activity summary .act-summary-step {
    color: var(--fg-faint); margin-left: 6px;
    display: inline-block; max-width: 60%; overflow: hidden;
    text-overflow: ellipsis; white-space: nowrap; vertical-align: bottom;
}
.act-list { padding: 2px 8px 6px 10px; display: flex; flex-direction: column; gap: 3px; }
.act-item { font-size: 11.5px; color: var(--fg-muted); display: flex; gap: 6px; align-items: baseline; }
.act-item .act-icon { flex: 0 0 auto; width: 14px; text-align: center; opacity: 0.9; }
.act-item .act-text { flex: 1 1 auto; word-break: break-word; }
.act-item .act-text code {
    font-family: 'JetBrains Mono', monospace; font-size: 11px;
    background: var(--code-bg, rgba(128,128,128,0.12)); padding: 0 3px; border-radius: 3px;
}
.act-item.act-running .act-text { color: var(--fg); }
.act-item .act-time { color: var(--fg-faint); font-size: 10.5px; margin-left: 4px; white-space: nowrap; }
.act-item .act-purpose { color: var(--fg-faint); font-style: italic; }
.act-raw {
    margin: 1px 0 3px 20px; padding: 3px 6px; max-height: 140px; overflow: auto;
    font-family: 'JetBrains Mono', monospace; font-size: 10.5px; line-height: 1.35;
    color: var(--fg-faint); background: rgba(128,128,128,0.07); border-radius: 4px;
    white-space: pre-wrap; word-break: break-word;
}
.act-live-dots { display: inline-flex; gap: 2px; margin-left: 4px; vertical-align: middle; }
.act-live-dots span {
    width: 3px; height: 3px; border-radius: 50%; background: var(--accent);
    animation: act-blink 1.2s infinite ease-in-out;
}
.act-live-dots span:nth-child(2) { animation-delay: 0.2s; }
.act-live-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes act-blink { 0%, 80%, 100% { opacity: 0.25; } 40% { opacity: 1; } }
/* files the agent wrote in this turn */
.file-changes {
    align-self: flex-start; width: 95%; max-width: 95%; margin: 4px 0;
    border: 1px solid var(--border); border-radius: 8px; overflow: hidden;
}
.file-changes .fc-head {
    font-size: 11px; font-weight: 600; color: var(--fg-muted);
    padding: 4px 10px; background: rgba(128,128,128,0.08);
}
.fc-item {
    display: flex; align-items: center; gap: 6px;
    padding: 5px 10px; font-size: 12px; border-top: 1px solid var(--border);
}
.fc-item .fc-name { font-weight: 600; color: var(--fg); white-space: nowrap; }
.fc-item .fc-dir {
    color: var(--fg-faint); font-size: 10.5px; flex: 1 1 auto; min-width: 0;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.fc-item .fc-status { font-size: 10.5px; color: var(--fg-faint); }
.fc-item .fc-status.fc-status-error { color: var(--danger); }
.fc-item.fc-reverted .fc-name { text-decoration: line-through; color: var(--fg-faint); }
.fc-actions { display: flex; gap: 4px; flex: 0 0 auto; }
.fc-btn {
    font-size: 11px; padding: 2px 8px; border-radius: 4px; cursor: pointer;
    background: var(--menu-bg); color: var(--fg); border: 1px solid var(--border);
}
.fc-btn:hover:not(:disabled) { background: var(--menu-hover-bg); }
.fc-btn:disabled { opacity: 0.45; cursor: default; }
.fc-btn.fc-danger { color: var(--danger); border-color: var(--danger); }
#image-preview { display: flex; gap: 4px; flex-wrap: wrap; padding: 4px 12px; background: var(--preview-bg); }
#image-preview:empty { display: none; }
#image-preview img { max-height: 60px; border-radius: 4px; border: 1px solid var(--preview-border); }
#image-preview .img-wrap { position: relative; display: inline-block; }
#image-preview .img-remove {
    position: absolute; top: -4px; right: -4px; background: var(--danger); color: #fff;
    border: none; border-radius: 50%; width: 16px; height: 16px; font-size: 10px;
    cursor: pointer; display: flex; align-items: center; justify-content: center;
}
/* input container */
#input-container {
    background: var(--bg);
    border-top: 1px solid var(--border);
    padding: 8px;
}
#input-wrapper {
    display: flex;
    flex-direction: column;
    gap: 6px;
    background: var(--input-bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 8px 10px;
    transition: border-color 0.15s, box-shadow 0.15s;
}
#input-wrapper:focus-within {
    border-color: var(--accent);
    box-shadow: 0 0 0 1px var(--accent-shadow);
}
/* input field - contenteditable */
#input {
    width: 100%; background: transparent; color: var(--fg); border: none;
    font-size: 13px;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    min-height: 60px; max-height: 120px; line-height: 1.6; padding: 0;
    overflow-y: auto; outline: none; white-space: pre-wrap; word-wrap: break-word;
}
#input:empty::before {
    content: attr(data-placeholder);
    color: var(--placeholder-fg);
    pointer-events: none;
}
/* inline badge styles */
.inline-badge {
    display: inline-flex; align-items: center; gap: 2px;
    padding: 1px 6px; border-radius: 4px; font-size: 12px;
    vertical-align: baseline; margin: 0 2px; white-space: nowrap;
    user-select: all; cursor: default;
}
.inline-badge.file {
    background: var(--badge-file-bg); color: var(--badge-file-fg);
    border: 1px solid var(--badge-file-border);
}
.inline-badge.agent {
    background: var(--badge-agent-bg); color: var(--badge-agent-fg);
    border: 1px solid var(--badge-agent-border);
}
.inline-badge.symbol {
    background: var(--badge-file-bg); color: var(--badge-file-fg);
    border: 1px dashed var(--badge-file-border);
}
/* toolbar */
#input-toolbar {
    display: flex; align-items: center; gap: 6px;
}
/* attach button */
#attach-btn {
    width: 24px; height: 24px; min-width: 24px;
    background: transparent; border: none; color: var(--icon-btn-fg); cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    border-radius: 4px; transition: color 0.15s, background 0.15s;
    flex-shrink: 0;
}
#attach-btn:hover { color: var(--icon-btn-hover-fg); background: var(--icon-btn-hover-bg); }
#attach-btn svg { width: 14px; height: 14px; }
/* model selector button */
.model-wrap { position: relative; flex-shrink: 0; margin-left: auto; }
#model-btn {
    background: transparent; color: var(--icon-btn-fg); border: none;
    border-radius: 4px; padding: 4px 8px; font-size: 11px; cursor: pointer;
    transition: color 0.15s, background 0.15s; white-space: nowrap;
}
#model-btn:hover { color: var(--icon-btn-hover-fg); background: var(--icon-btn-hover-bg); }
/* send button */
#send-btn {
    width: 24px; height: 24px; min-width: 24px;
    background: var(--accent); color: #fff; border: none; border-radius: 4px;
    cursor: pointer; display: flex; align-items: center; justify-content: center;
    transition: background 0.15s, opacity 0.15s; flex-shrink: 0;
}
#send-btn.stop-btn { background: var(--stop-bg); }
#send-btn:hover { background: var(--accent-hover); }
#send-btn.stop-btn:hover { background: var(--fg-muted); }
#send-btn:disabled { opacity: 0.3; cursor: not-allowed; }
#send-btn svg { width: 12px; height: 12px; }
#model-menu {
    display: none; position: absolute; bottom: 100%; right: 0; margin-bottom: 4px;
    background: var(--menu-bg); border: 1px solid var(--menu-border); border-radius: 6px;
    min-width: 160px; box-shadow: 0 4px 12px rgba(0,0,0,0.3); z-index: 999;
}
#model-menu .m-item {
    padding: 6px 12px; font-size: 12px; color: var(--fg); cursor: pointer;
}
#model-menu .m-item:hover { background: var(--menu-hover-bg); }
#model-menu .m-item.active { color: var(--accent); }
#autocomplete {
    display: none; position: fixed; background: var(--menu-bg); border: 1px solid var(--menu-border);
    border-radius: 6px; max-height: 240px; overflow-y: auto; z-index: 999;
    min-width: 280px; max-width: 400px; box-shadow: 0 4px 16px rgba(0,0,0,0.3);
}
#autocomplete .ac-item {
    display: flex; align-items: flex-start; gap: 8px;
    padding: 6px 10px; font-size: 12px; color: var(--fg); cursor: pointer;
}
#autocomplete .ac-item:hover, #autocomplete .ac-item.selected { background: var(--menu-hover-bg); }
#autocomplete .ac-icon { width: 16px; flex-shrink: 0; text-align: center; }
#autocomplete .ac-content { flex: 1; min-width: 0; }
#autocomplete .ac-name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
#autocomplete .ac-secondary { font-size: 10px; color: var(--fg-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
#autocomplete .ac-item.selected .ac-secondary { color: var(--fg-faint); }
#autocomplete .ac-match { color: #e8a838; font-weight: 600; }
#autocomplete .ac-section {
    font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px;
    color: var(--fg-faint); padding: 5px 10px 2px; user-select: none;
    position: sticky; top: 0; background: var(--menu-bg);
}
#autocomplete .ac-section:not(:first-child) { border-top: 1px solid var(--menu-border); margin-top: 2px; }
/* context tags area - unused */
#context-tags { display: none; }
.typing-indicator { display: inline-flex; gap: 4px; padding: 4px 0; }
.typing-indicator span {
    width: 6px; height: 6px; background: var(--fg-muted); border-radius: 50%;
    animation: bounce 1.4s infinite ease-in-out;
}
.typing-indicator span:nth-child(1) { animation-delay: 0s; }
.typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.4s; }
@keyframes bounce {
    0%,80%,100% { transform: scale(0.6); opacity: 0.4; }
    40% { transform: scale(1); opacity: 1; }
}
.typing-indicator-standalone {
    display: inline-flex; gap: 4px; padding: 10px 14px;
    align-self: flex-start;
}
.typing-indicator-standalone span {
    width: 6px; height: 6px; background: var(--fg-muted); border-radius: 50%;
    animation: bounce 1.4s infinite ease-in-out;
}
.typing-indicator-standalone span:nth-child(1) { animation-delay: 0s; }
.typing-indicator-standalone span:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator-standalone span:nth-child(3) { animation-delay: 0.4s; }
.error-text { color: var(--danger-fg); }
</style>
</head>
<body class="$initialBodyClass">
<div id="messages"></div>
<div id="image-preview"></div>
<div id="input-container">
    <div id="input-wrapper">
        <div id="input" contenteditable="true" data-placeholder=""></div>
        <div id="input-toolbar">
            <button id="attach-btn" title="Attach image (Ctrl+V)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/>
                </svg>
            </button>
            <div class="model-wrap">
                <button id="model-btn">Auto ▾</button>
                <div id="model-menu"></div>
            </div>
            <button id="send-btn" onclick="sendMessage()" title="Send (Enter)">
                <svg viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
            </button>
        </div>
    </div>
</div>
<div id="autocomplete"></div>
<input type="file" id="file-input" accept="image/*" multiple style="display:none">
<script>
const API = 'http://127.0.0.1:$port';
const SESSION = '$sessionId';
const messagesDiv = document.getElementById('messages');
const input = document.getElementById('input');
const sendBtn = document.getElementById('send-btn');
const modelBtn = document.getElementById('model-btn');
const modelMenu = document.getElementById('model-menu');
const autocompleteDiv = document.getElementById('autocomplete');
const imagePreview = document.getElementById('image-preview');
const fileInput = document.getElementById('file-input');
const attachBtn = document.getElementById('attach-btn');
let isStreaming = false;
let currentAssistantDiv = null;
let currentContent = '';
let currentAssistantWrap = null;
let currentSysToggle = null;
let currentActivityItem = null;
let currentActivityRaw = null;
let currentFileBlock = null;
let sysLogCount = 0;
let openFiles = [];
let excludedPaths = new Set();
let attachedImages = [];
let acSelectedIndex = -1;
let currentModel = 'auto';
let projectFiles = [];
let agents = [];
let i18n = { placeholder: '', openFile: '', systemLog: '' };

// Theme switching
function setTheme(name) {
    document.body.classList.remove('theme-dark', 'theme-light');
    document.body.classList.add('theme-' + name);
}

// i18n load
fetch(API + '/api/i18n').then(r => r.json()).then(data => {
    i18n = data;
    input.dataset.placeholder = i18n.placeholder || 'Enter message...';
}).catch(() => {
    input.dataset.placeholder = 'Enter message...';
});

// Skip patterns (lines to not display)
const SKIP_PATTERNS = [
    /^\s*$/
];

function shouldSkipLine(line) {
    const trimmed = line.trim();
    return SKIP_PATTERNS.some(p => p.test(trimmed));
}

// --- model selector (opens upward) ---
// 초기값은 내장 fallback — /api/models 응답(kiro-cli와 동기화, 서버에서 캐싱)이 오면 교체
let MODELS = [
    { value: 'auto', label: 'Auto' },
    { value: 'claude-sonnet-4.5', label: 'Claude Sonnet 4.5' },
    { value: 'claude-sonnet-4', label: 'Claude Sonnet 4' },
    { value: 'claude-haiku-4.5', label: 'Claude Haiku 4.5' },
    { value: 'deepseek-3.2', label: 'DeepSeek 3.2' },
    { value: 'minimax-m2.5', label: 'MiniMax M2.5' },
    { value: 'minimax-m2.1', label: 'MiniMax M2.1' },
    { value: 'glm-5', label: 'GLM 5' },
    { value: 'qwen3-coder-next', label: 'Qwen3 Coder Next' },
];
function renderModelMenu() {
    modelMenu.innerHTML = '';
    MODELS.forEach(m => {
        const item = document.createElement('div');
        item.className = 'm-item' + (m.value === currentModel ? ' active' : '');
        item.textContent = m.label;
        if (m.description) item.title = m.description;
        item.onclick = (e) => {
            e.stopPropagation();
            currentModel = m.value;
            modelBtn.textContent = m.label + ' ▾';
            modelMenu.style.display = 'none';
            fetch(API + '/api/set-model', { method: 'POST', body: SESSION + '\n' + m.value }).catch(() => {});
            renderModelMenu();
        };
        modelMenu.appendChild(item);
    });
}
renderModelMenu();
fetch(API + '/api/models').then(r => r.json()).then(list => {
    if (Array.isArray(list) && list.length > 0) {
        MODELS = list;
        renderModelMenu();
        const cur = MODELS.find(m => m.value === currentModel);
        if (cur) modelBtn.textContent = cur.label + ' ▾';
    }
}).catch(() => {});
modelBtn.onclick = (e) => {
    e.stopPropagation();
    modelMenu.style.display = modelMenu.style.display === 'block' ? 'none' : 'block';
};
document.addEventListener('click', () => { modelMenu.style.display = 'none'; });

// contenteditable input events
let isComposing = false;
input.addEventListener('compositionstart', () => { isComposing = true; });
input.addEventListener('compositionend', () => { isComposing = false; handleAutocomplete(); });
// paste as plain text only
input.addEventListener('paste', (e) => {
    e.preventDefault();
    const text = (e.clipboardData || window.clipboardData).getData('text/plain');
    document.execCommand('insertText', false, text);
});
input.addEventListener('input', () => {
    if (!isComposing) handleAutocomplete();
});
input.addEventListener('keydown', (e) => {
    if (isComposing) return;
    handleKey(e);
});

function handleKey(e) {
    if (autocompleteDiv.style.display === 'block') {
        const items = autocompleteDiv.querySelectorAll('.ac-item');
        if (e.key === 'ArrowDown') { e.preventDefault(); acSelectedIndex = Math.min(acSelectedIndex+1, items.length-1); updateAcSelection(items); return; }
        if (e.key === 'ArrowUp') { e.preventDefault(); acSelectedIndex = Math.max(acSelectedIndex-1, 0); updateAcSelection(items); return; }
        if ((e.key === 'Enter' || e.key === 'Tab') && acSelectedIndex >= 0 && items[acSelectedIndex]) {
            e.preventDefault();
            const item = items[acSelectedIndex];
            if (acType === 'command') {
                insertCommandAutocomplete(item.dataset.cmd);
            } else if (acType === 'mention') {
                insertMention(item.dataset.mtype, item.dataset.value, item.dataset.display);
            }
            return;
        }
        if (e.key === 'Escape') { hideAutocomplete(); return; }
    }
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
}

// extract text from contenteditable (badges as #name or @name)
function getInputText() {
    let text = '';
    input.childNodes.forEach(node => {
        if (node.nodeType === Node.TEXT_NODE) {
            text += node.textContent;
        } else if (node.nodeType === Node.ELEMENT_NODE) {
            if (node.classList.contains('inline-badge')) {
                const type = node.dataset.type;
                const name = node.dataset.name;
                // the CLI expands '@<path>' / '@<agent>'; '#<path>' is ignored by it
                text += '@' + name;
            } else {
                text += node.textContent;
            }
        }
    });
    return text;
}

function setInput(text) {
    input.textContent = text;
    input.dispatchEvent(new Event('input'));
    input.focus();
    // move cursor to end
    const range = document.createRange();
    const sel = window.getSelection();
    range.selectNodeContents(input);
    range.collapse(false);
    sel.removeAllRanges();
    sel.addRange(range);
}

function sendMessage() {
    const text = getInputText().trim();
    if (!text || isStreaming) return;
    hideAutocomplete();

    let fullMessage = text;

    // always include active file as context
    const activeFile = openFiles.find(f => f.active);
    if (activeFile) {
        fullMessage = '[Current file: ' + activeFile.relativePath + ']\n' + fullMessage;
    }

    if (attachedImages.length > 0) {
        const paths = attachedImages.map(img => img.savedPath || img.name).join(', ');
        fullMessage = '[Attached images: ' + paths + ']\n' + fullMessage;
    }

    addUserMessage(text);
    input.innerHTML = '';
    attachedImages = []; imagePreview.innerHTML = '';
    setEnabled(false);
    stopDraining = false; // 직전 중지의 드레인이 진행 중이어도 새 메시지 이벤트는 렌더링
    fetch(API + '/api/send', { method: 'POST', body: SESSION + '\n' + fullMessage })
        .catch(err => { addErrorMessage('Send failed: ' + err.message); setEnabled(true); });
    schedulePoll(); // isStreaming=true 상태이므로 150ms 폴링으로 즉시 전환
}

function addUserMessage(text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg-wrap user-wrap';
    const div = document.createElement('div');
    div.className = 'msg user'; div.textContent = text;
    const copyBtn = document.createElement('button');
    copyBtn.className = 'copy-btn'; copyBtn.textContent = '📋'; copyBtn.title = 'Copy';
    copyBtn.onclick = () => { navigator.clipboard.writeText(text); copyBtn.textContent = '✓'; setTimeout(() => copyBtn.textContent = '📋', 1000); };
    wrap.appendChild(div); wrap.appendChild(copyBtn);
    messagesDiv.appendChild(wrap); scrollToBottom();
}

let typingIndicator = null;

function startAssistantMessage() {
    currentContent = ''; sysLogCount = 0; currentSysToggle = null;
    currentActivityItem = null; currentActivityRaw = null;
    currentFileBlock = null;
    currentAssistantWrap = null;
    currentAssistantDiv = null;
    typingIndicator = document.createElement('div');
    typingIndicator.className = 'typing-indicator-standalone';
    typingIndicator.innerHTML = '<span></span><span></span><span></span>';
    messagesDiv.appendChild(typingIndicator);
    scrollToBottom();
}

function ensureAssistantBubble() {
    if (currentAssistantDiv) return;
    if (typingIndicator) { typingIndicator.remove(); typingIndicator = null; }
    currentAssistantWrap = document.createElement('div');
    currentAssistantWrap.className = 'msg-wrap';
    currentAssistantDiv = document.createElement('div');
    currentAssistantDiv.className = 'msg assistant streaming';
    const copyBtn = document.createElement('button');
    copyBtn.className = 'copy-btn'; copyBtn.textContent = '📋'; copyBtn.title = 'Copy';
    copyBtn.onclick = () => { navigator.clipboard.writeText(currentContent.replace(/▸\s*Time:\s*[\d.]+s/g, '').trim()); copyBtn.textContent = '✓'; setTimeout(() => copyBtn.textContent = '📋', 1000); };
    currentAssistantWrap.appendChild(currentAssistantDiv);
    currentAssistantWrap.appendChild(copyBtn);
    messagesDiv.appendChild(currentAssistantWrap);
}

function renderContent(raw) {
    let display = raw
        .replace(/▸\s*Time:\s*[\d.]+s/g, '')
        .replace(/Completed in [\d.]+s\.?/gi, '')
        .replace(/^-{3,}$/gm, '')
        .replace(/\x1B\[[0-9;]*[a-zA-Z]/g, '')
        .replace(/\[0m/g, '')
        .replace(/\[38;5;\d+m/g, '')
        .replace(/\[\d+m/g, '')
        .replace(/\*\*([^*]+)\*\*/g, '$1')
        .trim();

    if (!display) return '';

    display = autoWrapCodeBlocks(display);

    try {
        if (typeof marked !== 'undefined' && marked.parse) {
            return marked.parse(display);
        }
        return simpleMarkdown(display);
    }
    catch(e) {
        console.error('Markdown parse error:', e);
        return simpleMarkdown(display);
    }
}

// kiro-cli --no-interactive output: lang hint line -> code -> double blank
const CODE_LANG_HINTS = /^(kotlin|java|python|javascript|typescript|bash|sh|shell|sql|xml|json|yaml|yml|html|css|swift|rust|go|c|cpp|ruby|php|scala|groovy|dart|text|plaintext|diff|makefile|dockerfile|toml|ini|properties|gradle)\s*$/i;

function autoWrapCodeBlocks(text) {
    if (text.includes('```')) return text;

    const lines = text.split('\n');
    const result = [];
    let i = 0;

    while (i < lines.length) {
        const trimmed = lines[i].trim();

        if (CODE_LANG_HINTS.test(trimmed)) {
            const lang = trimmed.toLowerCase();
            const codeLines = [];
            i++;

            let emptyCount = 0;
            while (i < lines.length) {
                const line = lines[i];
                const lt = line.trim();

                if (lt === '') {
                    emptyCount++;
                    if (emptyCount >= 2) { i++; break; }
                    codeLines.push(line);
                    i++;
                    continue;
                }

                emptyCount = 0;

                if (isNaturalLanguage(lt)) {
                    break;
                }

                codeLines.push(line);
                i++;
            }

            while (codeLines.length > 0 && codeLines[codeLines.length - 1].trim() === '') {
                codeLines.pop();
            }

            if (codeLines.length > 0) {
                result.push('```' + lang);
                result.push(...codeLines);
                result.push('```');
            }
        } else {
            result.push(lines[i]);
            i++;
        }
    }

    return result.join('\n');
}

function isNaturalLanguage(line) {
    if (!line || line.length < 3) return false;
    if (/[가-힯぀-ゟ゠-ヿ一-鿿]/.test(line)) return true;
    if (/^[A-Z][a-z]+\s+[a-z]+\s/.test(line)) return true;
    if (/^(The|This|It|Here|Note|See|For|If|When|After|Before|You|We|I)\s/i.test(line)) return true;
    if (/^\d+\.\s+[A-Za-z가-힯]/.test(line)) return true;
    if (/^[-*]\s+[A-Za-z가-힯]/.test(line)) return true;
    if (/^>\s/.test(line)) return true;
    return false;
}

// simple markdown fallback
function simpleMarkdown(text) {
    let html = escapeHtml(text);

    html = html.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>');
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
    html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
    html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
    html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
    html = html.replace(/^- (.+)$/gm, '<li>$1</li>');
    html = html.replace(/^(\d+)\. (.+)$/gm, '<li>$2</li>');

    const lines = html.split('\n');
    let inTable = false;
    let tableHtml = '';
    const result = [];

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (line.includes('|') && line.trim().startsWith('|')) {
            if (!inTable) {
                inTable = true;
                tableHtml = '<table>';
            }
            if (/^\|[\s\-:|]+\|$/.test(line.trim())) continue;

            const cells = line.split('|').filter(c => c.trim());
            const isHeader = i === 0 || (i > 0 && /^\|[\s\-:|]+\|$/.test(lines[i-1]?.trim() || ''));
            const tag = isHeader ? 'th' : 'td';
            tableHtml += '<tr>' + cells.map(c => '<' + tag + '>' + c.trim() + '</' + tag + '>').join('') + '</tr>';
        } else {
            if (inTable) {
                tableHtml += '</table>';
                result.push(tableHtml);
                tableHtml = '';
                inTable = false;
            }
            result.push(line);
        }
    }
    if (inTable) {
        tableHtml += '</table>';
        result.push(tableHtml);
    }

    html = result.join('\n');
    html = html.replace(/\n/g, '<br>');
    html = html.replace(/(<br>){3,}/g, '<br><br>');

    return html;
}

// ---- activity trace (live agent steps) -------------------------------------
const TOOL_ICONS = {
    shell: '▶', execute_bash: '▶', bash: '▶', cmd: '▶',
    read: '📖', fs_read: '📖', file_read: '📖',
    write: '✍', fs_write: '✍', file_write: '✍', edit: '✍',
    code: '🔍', search: '🔍', grep: '🔍', glob: '🔍',
    use_aws: '☁', aws: '☁',
    browser: '🌐', web: '🌐', fetch: '🌐',
    introspect: '💭', knowledge: '📚', spawn: '🤖'
};

function toolIcon(name) {
    const key = String(name || '').toLowerCase().trim();
    if (TOOL_ICONS[key]) return TOOL_ICONS[key];
    for (const k in TOOL_ICONS) {
        if (key.indexOf(k) !== -1) return TOOL_ICONS[k];
    }
    return '🔧';
}

// One [SYS] line -> structured event. Mirrors the patterns KiroCliProcess.isSystemOutput detects.
function parseActivity(text) {
    const t = String(text).trim();
    if (!t) return { kind: 'skip' };

    const m = t.match(/^(.*?)\s*\(using tool:\s*([^)]+)\)\s*$/);
    if (m) {
        const tool = m[2].trim();
        return { kind: 'tool', icon: toolIcon(tool), label: m[1].trim() || ('Tool: ' + tool) };
    }
    if (/^-\s*Completed in/i.test(t)) return { kind: 'done', label: t.replace(/^-\s*/, '') };
    if (/^(Purpose|목적)\s*:/i.test(t)) return { kind: 'purpose', label: t.replace(/^[^:]*:\s*/, '') };
    if (/^(✓|✔|●|✗|✘)/.test(t)) return { kind: 'ok', icon: t.charAt(0), label: t.substring(1).trim() };
    if (/^(Searching for symbols|Looking up symbols|Found\s+\d+\s+of\s+\d+\s+symbols)/i.test(t)) {
        return { kind: 'step', icon: '🔍', label: t };
    }
    if (/^Reading (file|directory|multiple)/i.test(t)) return { kind: 'step', icon: '📖', label: t };
    if (/^(Creating|Updating|Writing|Appending|Replacing)\b/i.test(t)) return { kind: 'step', icon: '✍', label: t };
    if (/(▸\s*Time:|Credits:)/.test(t)) return { kind: 'meta', label: t.replace(/^[▸\s]*/, '') };
    if (/Loading\.\.\.|^Thinking/i.test(t)) return { kind: 'skip' };
    return { kind: 'raw', label: t };
}

// Highlight quoted strings and path-like tokens inside an activity label.
function activityLabelHtml(label) {
    let html = escapeHtml(String(label));
    html = html.replace(/"([^"]{1,160})"/g, function (whole, inner) {
        return '<code>' + inner + '</code>';
    });
    html = html.replace(/(^|\s)((?:\/|~\/|\.\/)[^\s,;:]{2,160})/g, function (whole, pre, path) {
        return pre + '<code>' + path + '</code>';
    });
    return html;
}

function activityTitle(live) {
    if (live) return i18n.thinking || 'Working';
    return i18n.activity || i18n.systemLog || 'Activity';
}

function ensureActivityBlock() {
    if (currentSysToggle) return currentSysToggle;
    currentSysToggle = document.createElement('details');
    currentSysToggle.className = 'sys-toggle activity';
    currentSysToggle.dataset.live = '1';
    currentSysToggle.open = true; // auto-expand while the agent is working
    const summary = document.createElement('summary');
    const label = document.createElement('span');
    label.className = 'act-summary-label';
    const step = document.createElement('span');
    step.className = 'act-summary-step';
    summary.appendChild(label);
    summary.appendChild(step);
    currentSysToggle.appendChild(summary);
    const list = document.createElement('div');
    list.className = 'act-list';
    currentSysToggle.appendChild(list);
    messagesDiv.appendChild(currentSysToggle);
    return currentSysToggle;
}

function updateActivitySummary(stepText) {
    if (!currentSysToggle) return;
    const live = currentSysToggle.dataset.live === '1';
    const label = currentSysToggle.querySelector('.act-summary-label');
    const step = currentSysToggle.querySelector('.act-summary-step');
    label.textContent = activityTitle(live) + (sysLogCount ? ' (' + sysLogCount + ')' : '');
    if (live) {
        const dots = document.createElement('span');
        dots.className = 'act-live-dots';
        dots.innerHTML = '<span></span><span></span><span></span>';
        label.appendChild(dots);
    }
    if (stepText) currentSysToggle.dataset.step = String(stepText);
    // while expanded the list already shows the steps, so only show the tail when collapsed
    step.textContent = (live && !currentSysToggle.open) || !live
        ? (currentSysToggle.dataset.step || '')
        : '';
}

function activityText(item) { return item.querySelector('.act-text'); }

function appendActivity(text) {
    const ev = parseActivity(text);
    if (ev.kind === 'skip') return;
    ensureActivityBlock();
    const list = currentSysToggle.querySelector('.act-list');

    // timing / cost lines annotate the step in flight instead of adding a row
    if ((ev.kind === 'done' || ev.kind === 'meta') && currentActivityItem) {
        let badge = activityText(currentActivityItem).querySelector('.act-time');
        if (!badge) {
            badge = document.createElement('span');
            badge.className = 'act-time';
            activityText(currentActivityItem).appendChild(badge);
        }
        badge.textContent = ' ' + ev.label;
        currentActivityItem.classList.remove('act-running');
        updateActivitySummary(null);
        return;
    }
    if (ev.kind === 'purpose' && currentActivityItem) {
        const p = document.createElement('span');
        p.className = 'act-purpose';
        p.textContent = ' — ' + ev.label;
        activityText(currentActivityItem).appendChild(p);
        return;
    }
    if (ev.kind === 'raw' || ev.kind === 'done' || ev.kind === 'meta') {
        if (!currentActivityRaw) {
            currentActivityRaw = document.createElement('div');
            currentActivityRaw.className = 'act-raw';
            list.appendChild(currentActivityRaw);
        }
        currentActivityRaw.textContent += (currentActivityRaw.textContent ? '\n' : '') + ev.label;
        currentActivityRaw.scrollTop = currentActivityRaw.scrollHeight;
        return;
    }

    sysLogCount++;
    const item = document.createElement('div');
    item.className = 'act-item' + (ev.kind === 'ok' ? '' : ' act-running');
    const icon = document.createElement('span');
    icon.className = 'act-icon';
    icon.textContent = ev.icon || '•';
    const txt = document.createElement('span');
    txt.className = 'act-text';
    txt.innerHTML = activityLabelHtml(ev.label);
    item.appendChild(icon);
    item.appendChild(txt);
    list.appendChild(item);
    if (ev.kind !== 'ok') {
        if (currentActivityItem) currentActivityItem.classList.remove('act-running');
        currentActivityItem = item;
        currentActivityRaw = null;
    }
    updateActivitySummary(ev.label);
}

// Called when the final answer arrived: stop the live state and collapse the trace.
function finishActivity() {
    if (!currentSysToggle) return;
    currentSysToggle.dataset.live = '0';
    if (currentActivityItem) currentActivityItem.classList.remove('act-running');
    currentSysToggle.open = false;
    updateActivitySummary(null);
    currentSysToggle = null;
    currentActivityItem = null;
    currentActivityRaw = null;
}

// ---- files the agent wrote in this turn ------------------------------------
// The CLI runs with --trust-all-tools, so the file is already written by the time
// we hear about it. No pre-approval is possible (in non-interactive mode the CLI
// rejects a non-trusted tool instead of asking), so we offer diff + revert, backed
// by the IDE's Local History.
function ensureFileChangeBlock() {
    if (currentFileBlock) return currentFileBlock;
    currentFileBlock = document.createElement('div');
    currentFileBlock.className = 'file-changes';
    const head = document.createElement('div');
    head.className = 'fc-head';
    currentFileBlock.appendChild(head);
    const list = document.createElement('div');
    list.className = 'fc-list';
    currentFileBlock.appendChild(list);
    messagesDiv.appendChild(currentFileBlock);
    return currentFileBlock;
}

function updateFileChangeHead() {
    if (!currentFileBlock) return;
    const n = currentFileBlock.querySelectorAll('.fc-item').length;
    currentFileBlock.querySelector('.fc-head').textContent =
        (i18n.filesChanged || 'Files changed') + ' (' + n + ')';
}

function appendFileChange(path) {
    if (!path) return;
    ensureFileChangeBlock();
    const list = currentFileBlock.querySelector('.fc-list');
    if (list.querySelector('[data-path="' + CSS.escape(path) + '"]')) return;

    const item = document.createElement('div');
    item.className = 'fc-item';
    item.dataset.path = path;

    const name = document.createElement('span');
    name.className = 'fc-name';
    name.textContent = path.split('/').pop();
    name.title = path;

    const dir = document.createElement('span');
    dir.className = 'fc-dir';
    dir.textContent = path.substring(0, path.lastIndexOf('/'));

    const actions = document.createElement('span');
    actions.className = 'fc-actions';
    const diffBtn = document.createElement('button');
    diffBtn.className = 'fc-btn';
    diffBtn.textContent = i18n.viewDiff || 'Diff';
    diffBtn.onclick = () => fileAction('/api/file-diff', path, item, diffBtn);
    const revertBtn = document.createElement('button');
    revertBtn.className = 'fc-btn fc-danger';
    revertBtn.textContent = i18n.revert || 'Revert';
    revertBtn.onclick = () => fileAction('/api/file-revert', path, item, revertBtn);
    actions.appendChild(diffBtn);
    actions.appendChild(revertBtn);

    item.appendChild(name);
    item.appendChild(dir);
    item.appendChild(actions);
    list.appendChild(item);
    updateFileChangeHead();
    scrollToBottom();
}

function fileAction(endpoint, path, item, btn) {
    const isRevert = endpoint.indexOf('revert') !== -1;
    btn.disabled = true;
    fetch(API + endpoint, { method: 'POST', body: SESSION + '\n' + path })
        .then(r => r.json())
        .then(res => {
            if (res.error) { markFileStatus(item, '⚠ ' + res.error, true); btn.disabled = false; return; }
            if (isRevert) {
                item.classList.add('fc-reverted');
                markFileStatus(item, res.deleted
                    ? (i18n.deleted || 'deleted')
                    : (i18n.reverted || 'reverted'), false);
                item.querySelectorAll('.fc-btn').forEach(b => b.disabled = true);
            } else {
                btn.disabled = false;
            }
        })
        .catch(() => { markFileStatus(item, '⚠', true); btn.disabled = false; });
}

function markFileStatus(item, text, isError) {
    let tag = item.querySelector('.fc-status');
    if (!tag) {
        tag = document.createElement('span');
        tag.className = 'fc-status';
        item.querySelector('.fc-actions').before(tag);
    }
    tag.classList.toggle('fc-status-error', !!isError);
    tag.textContent = text;
}

function appendChunk(chunk) {
    const lines = chunk.split('\n');
    for (const line of lines) {
        if (shouldSkipLine(line)) continue;

        const isSys = line.startsWith('[SYS]');
        const displayLine = isSys ? line.substring(5) : line;

        if (isSys) {
            flushContent();
            appendActivity(displayLine);
            // keep the "working" indicator below the trace until real answer text arrives
            if (typingIndicator) messagesDiv.appendChild(typingIndicator);
        } else {
            currentContent += displayLine + '\n';
        }
    }
    flushContent();
    scrollToBottom();
}

function flushContent() {
    if (currentContent.trim()) {
        ensureAssistantBubble();
        currentAssistantDiv.innerHTML = renderContent(currentContent);
        addCodeCopyButtons(currentAssistantDiv);
        scrollToBottom();
    }
}

function addCodeCopyButtons(container) {
    container.querySelectorAll('pre').forEach(pre => {
        if (pre.querySelector('.code-copy')) return;
        const btn = document.createElement('button');
        btn.className = 'code-copy';
        btn.textContent = '📋';
        btn.title = 'Copy code';
        btn.onclick = (e) => {
            e.stopPropagation();
            const code = pre.querySelector('code') || pre;
            navigator.clipboard.writeText(code.textContent);
            btn.textContent = '✓';
            setTimeout(() => btn.textContent = '📋', 1000);
        };
        pre.appendChild(btn);
    });
}

function finishAssistantMessage() {
    if (typingIndicator) { typingIndicator.remove(); typingIndicator = null; }
    finishActivity();
    if (currentAssistantDiv) {
        currentAssistantDiv.classList.remove('streaming');
        if (currentContent.trim()) {
            currentAssistantDiv.innerHTML = renderContent(currentContent);
            addCodeCopyButtons(currentAssistantDiv);
        } else {
            currentAssistantWrap.remove();
        }
    }
    currentAssistantDiv = null;
    currentAssistantWrap = null;
    setEnabled(true);
}

function addErrorMessage(text) {
    if (typingIndicator) { typingIndicator.remove(); typingIndicator = null; }
    finishActivity();
    ensureAssistantBubble();
    if (currentAssistantDiv) {
        currentAssistantDiv.innerHTML = '<span class="error-text">⚠ ' + escapeHtml(text) + '</span>';
        currentAssistantDiv.classList.remove('streaming'); currentAssistantDiv = null;
    }
}

function setEnabled(en) {
    isStreaming = !en;
    // 생성 중에도 입력창은 편집 가능하게 유지 — 다음 메시지를 미리 입력해두고
    // 생성 완료 직후 바로 Enter로 보낼 수 있다 (생성 중 Enter는 sendMessage의 isStreaming 가드가 막음)

    // toggle send/stop button
    if (en) {
        sendBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>';
        sendBtn.classList.remove('stop-btn');
        sendBtn.onclick = () => sendMessage();
        sendBtn.title = 'Send (Enter)';
    } else {
        sendBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>';
        sendBtn.classList.add('stop-btn');
        sendBtn.onclick = () => stopGeneration();
        sendBtn.title = 'Stop';
    }
    sendBtn.disabled = false;
    if (en) input.focus();
}

function stopGeneration() {
    // 중지 시점까지 쌓인 미수신 이벤트를 렌더링 없이 드레인 (중지 후 잔여 말풍선 방지)
    stopDraining = true;
    fetch(API + '/api/stop', { method: 'POST', body: SESSION })
        .then(() => fetch(API + '/api/events?session=' + SESSION + '&after=' + eventCursor))
        .then(r => r.json())
        .then(evs => { for (const ev of evs) if (ev.seq > eventCursor) eventCursor = ev.seq; })
        .catch(() => {})
        .finally(() => { stopDraining = false; });
    finishAssistantMessage();
}
function scrollToBottom() { messagesDiv.scrollTop = messagesDiv.scrollHeight; }
function escapeHtml(s) { return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

// --- slash commands ---
const SLASH_COMMANDS = [
    { cmd: '/quit', desc: 'Quit the app' },
    { cmd: '/clear', desc: 'Clear conversation history' },
    { cmd: '/agent', desc: 'Manage agents' },
    { cmd: '/chat', desc: 'Manage saved conversations' },
    { cmd: '/context', desc: 'Manage context files' },
    { cmd: '/code', desc: 'LSP code intelligence' },
    { cmd: '/editor', desc: 'Compose prompt in editor' },
    { cmd: '/reply', desc: 'Reply to last response' },
    { cmd: '/compact', desc: 'Summarize conversation to free context' },
    { cmd: '/tools', desc: 'View tools and permissions' },
    { cmd: '/issue', desc: 'Create GitHub issue' },
    { cmd: '/logdump', desc: 'Generate log file' },
    { cmd: '/changelog', desc: 'View changelog' },
    { cmd: '/prompts', desc: 'View prompts' },
    { cmd: '/hooks', desc: 'View context hooks' },
    { cmd: '/usage', desc: 'Usage and credits info' },
    { cmd: '/mcp', desc: 'List MCP servers' },
    { cmd: '/model', desc: 'Select model' },
    { cmd: '/experiment', desc: 'Toggle experimental features' },
    { cmd: '/plan', desc: 'Switch to Plan agent' },
    { cmd: '/todos', desc: 'Manage todo list' },
    { cmd: '/paste', desc: 'Paste image from clipboard' },
    { cmd: '/help', desc: 'Show help' },
];

// file extension icons
const FILE_ICONS = {
    'kt': '📄', 'java': '☕', 'ts': '🔷', 'tsx': '🔷', 'js': '🟨', 'jsx': '🟨',
    'json': '📋', 'xml': '📰', 'md': '📝', 'yaml': '⚙️', 'yml': '⚙️',
    'gradle': '🐘', 'kts': '🐘', 'py': '🐍', 'rb': '💎', 'go': '🔵',
    'rs': '🦀', 'swift': '🍎', 'css': '🎨', 'html': '🌐', 'sql': '🗃️',
    'sh': '📜', 'bash': '📜', 'default': '📄'
};
function getFileIcon(filename) {
    const ext = filename.split('.').pop()?.toLowerCase() || '';
    return FILE_ICONS[ext] || FILE_ICONS['default'];
}

// --- autocomplete type ---
let acType = ''; // 'command', 'file', 'agent'
let acDebounceTimer = null;

// get text before cursor in contenteditable
function getTextBeforeCursor() {
    const sel = window.getSelection();
    if (!sel.rangeCount) return '';

    const range = sel.getRangeAt(0);
    const preRange = range.cloneRange();
    preRange.selectNodeContents(input);
    preRange.setEnd(range.startContainer, range.startOffset);

    let text = '';
    const walker = document.createTreeWalker(input, NodeFilter.SHOW_TEXT, null, false);
    let node;
    while ((node = walker.nextNode())) {
        if (preRange.intersectsNode(node)) {
            const nodeRange = document.createRange();
            nodeRange.selectNodeContents(node);
            if (preRange.compareBoundaryPoints(Range.END_TO_START, nodeRange) <= 0) {
                if (preRange.compareBoundaryPoints(Range.END_TO_END, nodeRange) >= 0) {
                    text += node.textContent;
                } else {
                    text += node.textContent.substring(0, preRange.endOffset - (node === range.startContainer ? 0 : 0));
                }
            }
        }
    }

    // simple fallback: text before cursor in current text node
    if (range.startContainer.nodeType === Node.TEXT_NODE) {
        return range.startContainer.textContent.substring(0, range.startOffset);
    }
    return '';
}

function handleAutocomplete() {
    const before = getTextBeforeCursor();

    // slash command autocomplete (line start only)
    const slashMatch = before.match(/^\/([^\s]*)$/) || before.match(/\n\/([^\s]*)$/);
    if (slashMatch) {
        const q = slashMatch[1].toLowerCase();
        const matches = SLASH_COMMANDS.filter(c => c.cmd.toLowerCase().includes('/' + q)).slice(0, 15);
        if (matches.length > 0) { showCommandAutocomplete(matches); return; }
    }

    // @ mention autocomplete: files + classes/symbols + agents in one menu.
    // '#' stays supported as a files-only alias (older muscle memory).
    const mentionMatch = before.match(/[@#]([^\s@#]*)$/);
    if (mentionMatch) {
        const q = mentionMatch[1];
        const filesOnly = before.charAt(before.length - q.length - 1) === '#';
        clearTimeout(acDebounceTimer);
        acDebounceTimer = setTimeout(() => searchMentions(q, filesOnly), 150);
        return;
    }

    hideAutocomplete();
}

// --- mentions (@) ---------------------------------------------------------
// The CLI itself expands '@<path>' into file context, so every mention is
// serialized with '@' (see getInputText). '#<path>' is ignored by the CLI.
function searchMentions(query, filesOnly) {
    const jobs = [
        fetch(API + '/api/project-files' + (query ? '?q=' + encodeURIComponent(query) : ''))
            .then(r => r.json()).catch(() => [])
    ];
    if (filesOnly) {
        jobs.push(Promise.resolve([]), Promise.resolve([]));
    } else {
        jobs.push(query.length >= 2
            ? fetch(API + '/api/project-symbols?q=' + encodeURIComponent(query)).then(r => r.json()).catch(() => [])
            : Promise.resolve([]));
        jobs.push(agents.length > 0
            ? Promise.resolve(agents)
            : fetch(API + '/api/agents').then(r => r.json()).then(d => { agents = d; return d; }).catch(() => []));
    }

    Promise.all(jobs).then(res => {
        const files = res[0] || [];
        const symbols = res[1] || [];
        const matchedAgents = (res[2] || []).filter(a => a.name.toLowerCase().includes(query.toLowerCase()));
        showMentionAutocomplete(query, files, symbols, matchedAgents);
    });
}

function mentionSection(title) {
    const head = document.createElement('div');
    head.className = 'ac-section';
    head.textContent = title;
    return head;
}

function mentionItem(icon, name, secondary, query, mtype, value, display) {
    const item = document.createElement('div');
    item.className = 'ac-item';
    item.dataset.mtype = mtype;
    item.dataset.value = value;
    item.dataset.display = display;
    item.innerHTML = '<span class="ac-icon">' + icon + '</span>' +
        '<div class="ac-content">' +
        '<div class="ac-name">' + highlightMatch(name, query) + '</div>' +
        '<div class="ac-secondary">' + escapeHtml(secondary || '') + '</div>' +
        '</div>';
    item.onclick = () => insertMention(mtype, value, display);
    return item;
}

function symbolIcon(kind) { return kind === 'class' ? '🅒' : '🔡'; }

function showMentionAutocomplete(query, files, symbols, matchedAgents) {
    acType = 'mention';
    autocompleteDiv.innerHTML = '';

    const limits = { file: 8, symbol: 8, agent: 4 };
    if (symbols.length === 0 && matchedAgents.length === 0) limits.file = 15;

    if (symbols.length > 0) {
        autocompleteDiv.appendChild(mentionSection(i18n.classes || 'Classes & symbols'));
        symbols.slice(0, limits.symbol).forEach(s => {
            autocompleteDiv.appendChild(mentionItem(
                symbolIcon(s.kind), s.name, s.location || s.path, query, 'symbol', s.path, s.name));
        });
    }
    if (files.length > 0) {
        autocompleteDiv.appendChild(mentionSection(i18n.files || 'Files'));
        files.slice(0, limits.file).forEach(f => {
            autocompleteDiv.appendChild(mentionItem(
                getFileIcon(f.name), f.name, f.dir, query, 'file', f.path, f.name));
        });
    }
    if (matchedAgents.length > 0) {
        autocompleteDiv.appendChild(mentionSection(i18n.agents || 'Agents'));
        matchedAgents.slice(0, limits.agent).forEach(a => {
            const desc = a.description.length > 50 ? a.description.substring(0, 50) + '...' : a.description;
            autocompleteDiv.appendChild(mentionItem('🤖', a.name, desc, query, 'agent', a.name, a.name));
        });
    }

    const items = autocompleteDiv.querySelectorAll('.ac-item');
    if (items.length === 0) { hideAutocomplete(); return; }
    acSelectedIndex = 0;
    updateAcSelection(items);
    positionAutocomplete();
}

function insertMention(mtype, value, display) {
    deleteTextBeforeCursor(/[@#][^\s@#]*$/);
    // symbols are sent as their containing file: that is what the CLI can expand
    insertBadgeAtCursor(mtype, value, display);
    hideAutocomplete(); input.focus();
}

// highlight matching text
function highlightMatch(text, query) {
    if (!query) return escapeHtml(text);
    const idx = text.toLowerCase().indexOf(query.toLowerCase());
    if (idx === -1) return escapeHtml(text);
    return escapeHtml(text.substring(0, idx)) +
           '<span class="ac-match">' + escapeHtml(text.substring(idx, idx + query.length)) + '</span>' +
           escapeHtml(text.substring(idx + query.length));
}

function showCommandAutocomplete(matches) {
    acType = 'command';
    autocompleteDiv.innerHTML = ''; acSelectedIndex = 0;
    matches.forEach((c, i) => {
        const item = document.createElement('div');
        item.className = 'ac-item' + (i === 0 ? ' selected' : '');
        item.innerHTML = '<span class="ac-icon" style="color:#4fc1ff">/</span>' +
            '<div class="ac-content">' +
            '<div class="ac-name" style="color:#4fc1ff">' + escapeHtml(c.cmd) + '</div>' +
            '<div class="ac-secondary">' + escapeHtml(c.desc) + '</div>' +
            '</div>';
        item.dataset.cmd = c.cmd;
        item.onclick = () => insertCommandAutocomplete(c.cmd);
        autocompleteDiv.appendChild(item);
    });
    positionAutocomplete();
}

function positionAutocomplete() {
    const rect = input.getBoundingClientRect();
    autocompleteDiv.style.left = rect.left + 'px';
    autocompleteDiv.style.bottom = (window.innerHeight - rect.top + 4) + 'px';
    autocompleteDiv.style.display = 'block';
}

function insertCommandAutocomplete(cmd) {
    // replace /query with cmd at cursor
    deleteTextBeforeCursor(/\/[^\s]*$/);
    insertTextAtCursor(cmd + ' ');
    hideAutocomplete(); input.focus();
}

// delete text before cursor matching pattern
function deleteTextBeforeCursor(pattern) {
    const sel = window.getSelection();
    if (!sel.rangeCount) return;

    const range = sel.getRangeAt(0);
    if (range.startContainer.nodeType !== Node.TEXT_NODE) return;

    const textNode = range.startContainer;
    const offset = range.startOffset;
    const text = textNode.textContent.substring(0, offset);
    const match = text.match(pattern);

    if (match) {
        const start = text.lastIndexOf(match[0]);
        textNode.textContent = textNode.textContent.substring(0, start) + textNode.textContent.substring(offset);
        range.setStart(textNode, start);
        range.collapse(true);
        sel.removeAllRanges();
        sel.addRange(range);
    }
}

// insert text at cursor
function insertTextAtCursor(text) {
    const sel = window.getSelection();
    if (!sel.rangeCount) return;

    const range = sel.getRangeAt(0);
    range.deleteContents();
    const textNode = document.createTextNode(text);
    range.insertNode(textNode);
    range.setStartAfter(textNode);
    range.collapse(true);
    sel.removeAllRanges();
    sel.addRange(range);
}

// insert badge at cursor
function insertBadgeAtCursor(type, name, displayName) {
    const sel = window.getSelection();
    if (!sel.rangeCount) return;

    const range = sel.getRangeAt(0);
    range.deleteContents();

    const badge = document.createElement('span');
    badge.className = 'inline-badge ' + type;
    badge.contentEditable = 'false';
    badge.dataset.type = type;
    badge.dataset.name = name;
    badge.innerHTML = '<span style="opacity:0.7">@</span>' + escapeHtml(displayName);

    range.insertNode(badge);

    // add space after badge
    const space = document.createTextNode(' ');
    badge.parentNode.insertBefore(space, badge.nextSibling);

    // move cursor after space
    range.setStartAfter(space);
    range.collapse(true);
    sel.removeAllRanges();
    sel.addRange(range);
}

function hideAutocomplete() { autocompleteDiv.style.display = 'none'; acSelectedIndex = -1; acType = ''; }
function updateAcSelection(items) {
    items.forEach((el, i) => el.classList.toggle('selected', i === acSelectedIndex));
    if (items[acSelectedIndex]) items[acSelectedIndex].scrollIntoView({ block: 'nearest' });
}

// --- image attach ---
attachBtn.onclick = () => fileInput.click();
fileInput.onchange = () => { for (const f of fileInput.files) addImage(f); fileInput.value = ''; };
document.addEventListener('paste', (e) => {
    const items = e.clipboardData?.items; if (!items) return;
    for (const item of items) {
        if (item.type.startsWith('image/')) { e.preventDefault(); addImage(item.getAsFile()); }
    }
});
function addImage(file) {
    const reader = new FileReader();
    reader.onload = (ev) => {
        const dataUrl = ev.target.result;
        fetch(API + '/api/save-image', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session: SESSION, name: file.name, data: dataUrl })
        }).then(r => r.text()).then(path => {
            attachedImages.push({ name: file.name, data: dataUrl, savedPath: path });
            renderImagePreviews();
        }).catch(() => {
            attachedImages.push({ name: file.name, data: dataUrl, savedPath: '' });
            renderImagePreviews();
        });
    };
    reader.readAsDataURL(file);
}
function renderImagePreviews() {
    imagePreview.innerHTML = '';
    attachedImages.forEach((img, i) => {
        const w = document.createElement('div'); w.className = 'img-wrap';
        w.innerHTML = '<img src="' + img.data + '" title="' + escapeHtml(img.name) + '"><button class="img-remove">&times;</button>';
        w.querySelector('.img-remove').onclick = () => { attachedImages.splice(i, 1); renderImagePreviews(); };
        imagePreview.appendChild(w);
    });
}

// --- event polling ---
// Remote Development에서는 SSE(끝나지 않는 응답)가 포워딩 계층을 통과하지 못하므로
// 커서 기반 폴링으로 이벤트를 가져온다 (생성 중 150ms, 대기 중 2s)
let eventCursor = 0;
let pollTimer = null;
let stopDraining = false; // 중지 직후 잔여 이벤트를 렌더링 없이 소비하는 중

function handleEvent(ev) {
    // 중지된 실행의 이후 출력은 백엔드(실행 세대 가드)에서 차단되므로 기본은 항상 렌더링.
    // isStreaming으로 걸러내면 늦게 도착하는 정상 응답까지 삼켜버리므로 안 됨.
    if (stopDraining) return; // 예외: 중지 드레인 중에는 커서만 전진
    if (ev.event === 'start') startAssistantMessage();
    else if (ev.event === 'chunk') appendChunk(ev.data);
    else if (ev.event === 'done') finishAssistantMessage();
    else if (ev.event === 'error') { if (ev.data) addErrorMessage(ev.data); finishAssistantMessage(); }
    else if (ev.event === 'file') appendFileChange(ev.data);
}

function pollEvents() {
    fetch(API + '/api/events?session=' + SESSION + '&after=' + eventCursor)
        .then(r => r.json())
        .then(evs => { for (const ev of evs) { eventCursor = ev.seq; handleEvent(ev); } })
        .catch(() => {})
        .finally(schedulePoll);
}

function schedulePoll() {
    clearTimeout(pollTimer);
    pollTimer = setTimeout(pollEvents, isStreaming ? 150 : 2000);
}

// 페이지 최초 로드: 과거 이벤트는 렌더링하지 않고 커서만 최신으로 맞춘 뒤 폴링 시작
function initEventPolling() {
    fetch(API + '/api/events?session=' + SESSION + '&after=0')
        .then(r => r.json())
        .then(evs => { for (const ev of evs) eventCursor = ev.seq; })
        .catch(() => {})
        .finally(schedulePoll);
}

// marked.js config (GFM table, line breaks)
if (typeof marked !== 'undefined') {
    const renderer = new marked.Renderer();
    // disable bold - output as plain text
    renderer.strong = (text) => text;
    // disable hr
    renderer.hr = () => '';

    marked.setOptions({
        breaks: true,
        gfm: true,
        headerIds: false,
        mangle: false,
        renderer: renderer
    });
} else {
    console.warn('marked.js not loaded, using fallback renderer');
}

// --- open files ---
function refreshOpenFiles() {
    fetch(API + '/api/open-files').then(r => r.json()).then(files => {
        const currentPaths = new Set(files.map(f => f.path));
        for (const p of excludedPaths) { if (!currentPaths.has(p)) excludedPaths.delete(p); }
        openFiles = files;
    }).catch(() => {});
}

refreshOpenFiles(); setInterval(refreshOpenFiles, 3000);
initEventPolling(); input.focus();
</script>
</body>
</html>
    """.trimIndent()
