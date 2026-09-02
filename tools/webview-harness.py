#!/usr/bin/env python3
"""Renderiza el webview de ChatPanel.kt fuera del IDE, para verificar cambios de UI.

Extrae el HTML/JS embebido de buildChatHtml(), neutraliza la red y lo alimenta con
datos reales capturados de kiro-cli 2.18.1. Genera tres páginas en /tmp/kiro-harness:

  live.html    traza de actividad mientras el agente trabaja (auto-expandida)
  done.html    traza colapsada + respuesta final renderizada
  mention.html menú de menciones '@' con secciones (clases / archivos / agentes)

Uso:
  python3 tools/webview-harness.py
  google-chrome --headless --disable-gpu --no-sandbox --hide-scrollbars \\
      --force-device-scale-factor=2 --window-size=760,900 \\
      --screenshot=/tmp/kiro-harness/live.png file:///tmp/kiro-harness/live.html
"""
import json
import pathlib

REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "src/main/kotlin/com/kiro/intellij/chat/ChatPanel.kt"
OUT = pathlib.Path("/tmp/kiro-harness")
OUT.mkdir(parents=True, exist_ok=True)

text = SRC.read_text(encoding="utf-8")
start = text.index('internal fun buildChatHtml')
start = text.index('"""', start) + 3
end = text.index('""".trimIndent()', start)
html = (text[start:end]
        .replace("$initialBodyClass", "theme-dark")
        .replace("$port", "1")
        .replace("$sessionId", "harness"))

# --- 1/2. traza de actividad ------------------------------------------------
# líneas reales del CLI, ya clasificadas por OutputEmitter ([SYS] = actividad)
CHUNKS = [
    "[SYS]I will run the following command: ls -la (using tool: shell)",
    "[SYS]Purpose: Listar contenido del directorio actual",
    "[SYS]total 44",
    "[SYS]drwxrwxr-x  3 rsfranco rsfranco  4096 sep  1 13:43 .",
    "[SYS] - Completed in 0.4s",
    "[SYS]Reading file: /tmp/kiro-cli-probe/src/Main.java, all lines (using tool: read)",
    "[SYS] \u2713 Successfully read 90 bytes from /tmp/kiro-cli-probe/src/Main.java",
    "[SYS] - Completed in 0.0s",
    '[SYS]Searching for symbols matching: "main" (using tool: code)',
    "[SYS]  1. Method main at src/Main.java:2:3",
    "[SYS] - Completed in 0.83s",
    "[SYS]I'll create the following file: /tmp/kiro-cli-probe/notas.txt (using tool: write)",
    "[SYS]Purpose: Crear notas.txt con la palabra ok",
    "[SYS]+    1: ok",
    "[SYS] - Completed in 0.0s",
]
ANSWER = 'La funci\u00f3n `main` en `src/Main.java` imprime **"hola"** en la consola.'

activity_driver = """
<script>
window.fetch = function () { return new Promise(function () {}); };
window.setTimeout = (function (orig) {
    return function (fn, ms) { if (ms === 150 || ms === 2000) return 0; return orig(fn, ms); };
})(window.setTimeout);

addUserMessage('busca la funcion main, leela y crea notas.txt');
startAssistantMessage();
__CHUNKS__.forEach(function (c) { appendChunk(c); });
if ('__STAGE__' === 'done') { appendChunk(__ANSWER__); finishAssistantMessage(); }
</script>
"""

for stage in ("live", "done"):
    body = (activity_driver.replace("__CHUNKS__", json.dumps(CHUNKS))
            .replace("__ANSWER__", json.dumps(ANSWER))
            .replace("__STAGE__", stage))
    (OUT / (stage + ".html")).write_text(html.replace("</body>", body + "\n</body>"), encoding="utf-8")
    print("wrote", OUT / (stage + ".html"))

# --- 3. menú de menciones (@) ----------------------------------------------
SYMBOLS = [
    {"name": "MainController", "path": "src/main/java/com/acme/web/MainController.java",
     "kind": "class", "location": "com.acme.web"},
    {"name": "MainService", "path": "src/main/java/com/acme/service/MainService.java",
     "kind": "class", "location": "com.acme.service"},
    {"name": "main", "path": "src/main/java/com/acme/Application.java",
     "kind": "symbol", "location": "Application"},
]
FILES = [
    {"name": "Main.java", "path": "src/main/java/com/acme/Main.java",
     "dir": "src/main/java/com/acme", "ext": "java"},
    {"name": "MainControllerTest.java", "path": "src/test/java/com/acme/web/MainControllerTest.java",
     "dir": "src/test/java/com/acme/web", "ext": "java"},
    {"name": "application-main.yml", "path": "src/main/resources/application-main.yml",
     "dir": "src/main/resources", "ext": "yml"},
]
AGENTS = [{"name": "maintainer", "description": "Revisa dependencias y actualiza el changelog"}]

mention_driver = """
<script>
window.fetch = function () { return new Promise(function () {}); };
i18n = { files: 'Files', classes: 'Classes & symbols', agents: 'Agents' };
addUserMessage('revisa @Main');
input.textContent = 'revisa @Main';
input.focus();
showMentionAutocomplete('Main', __FILES__, __SYMBOLS__, __AGENTS__);
</script>
"""
body = (mention_driver.replace("__FILES__", json.dumps(FILES))
        .replace("__SYMBOLS__", json.dumps(SYMBOLS))
        .replace("__AGENTS__", json.dumps(AGENTS)))
(OUT / "mention.html").write_text(html.replace("</body>", body + "\n</body>"), encoding="utf-8")
print("wrote", OUT / "mention.html")

# --- 4. bloque de archivos modificados (diff / revert) ---------------------
files_driver = """
<script>
window.fetch = function () { return new Promise(function () {}); };
i18n = { filesChanged: 'Files changed', viewDiff: 'Diff', revert: 'Revert',
         reverted: 'reverted', deleted: 'deleted' };
addUserModifiedDemo();
function addUserModifiedDemo() {
    addUserMessage('agrega un endpoint de health y actualiza el README');
    startAssistantMessage();
    appendChunk('[SYS]I will run the following command: ls (using tool: shell)');
    appendChunk('[SYS] - Completed in 0.2s');
    appendFileChange('/home/u/proj/src/main/java/com/acme/web/HealthController.java');
    appendFileChange('/home/u/proj/README.md');
    appendChunk('Listo: agregue el endpoint y actualice el README.');
    finishAssistantMessage();
    // simula que ya revertiste el segundo
    var items = document.querySelectorAll('.fc-item');
    items[1].classList.add('fc-reverted');
    markFileStatus(items[1], 'reverted', false);
    items[1].querySelectorAll('.fc-btn').forEach(function (b) { b.disabled = true; });
}
</script>
"""
(OUT / "files.html").write_text(html.replace("</body>", files_driver + "\n</body>"), encoding="utf-8")
print("wrote", OUT / "files.html")
