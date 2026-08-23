# Kiro with GUI — IntelliJ Plugin

[한국어](README_KO.md) | English

A GUI plugin that integrates [Kiro CLI](https://kiro.dev) into JetBrains IDEs.
Chat with kiro-cli directly inside your IDE, with diff viewer, editor context, and MCP server integration.

## Requirements

| Item | Version |
|------|---------|
| JetBrains IDE | 2025.1+ (IntelliJ IDEA, PyCharm, WebStorm, etc.) |
| kiro-cli | Required ([Install Guide](https://kiro.dev/docs/cli/)) |
| JCEF | The chat UI uses the IDE's embedded Chromium (bundled by default). On Linux, glibc 2.28+ is required — older distributions such as **Amazon Linux 2 are not supported** (workaround: Remote Development with a local JetBrains Client) |

## Installation

### From Marketplace

1. **Settings → Plugins → Marketplace**
2. Search "Kiro with GUI"
3. Install → Restart IDE

### From Local Build

```bash
git clone https://github.com/AwesomeHye/Intellij-Kiro-with-GUI.git
cd intellij-kiro-plugin
./gradlew buildPlugin
# Output: build/distributions/kiro-with-gui-0.1.0.zip
```

1. **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
2. Select the built zip file
3. Restart IDE

## Usage

### Chat

1. Click the **Kiro** Tool Window on the right side (or `Cmd+Esc`)
2. Type a message in the Chat tab
3. Select a model using the button below the input field

### Keyboard Shortcuts

| Shortcut (Mac) | Action |
|----------------|--------|
| `Cmd+Esc` | Open/focus Kiro Tool Window |
| `Cmd+Shift+K` | Send selected text to Kiro |
| `Cmd+Option+K` | Insert current file reference |

### Slash Commands

| Command | Description |
|---------|-------------|
| `/model [name]` | Check or change current model |
| `/clear` | Reset conversation history |
| `/status` | Check CLI status (path, version, auth) |
| `/help` | Help |
| `/context` | Manage context files (passed to CLI) |
| `/tools` | View tools and permissions (passed to CLI) |
| `/mcp` | MCP server list (passed to CLI) |
| `/compact` | Summarize conversation (passed to CLI) |

### Manage Tab

In the **Manage** tab of the Tool Window:

- **Auth** — Login/logout management
- **Settings** — kiro-cli path, default model, language, config directory
- **MCP** — MCP server list, add/remove/edit
- **Skills** — Available tools list and search
- **Agent** — Agent list, switch, create/edit

## Settings

### Application Level (All IDEs)

**Settings → Tools → Kiro** or Manage tab → Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Kiro command | `kiro-cli` | kiro-cli executable path |
| Default model | `Auto` | Default model |
| Auto-start | `true` | Auto-start on IDE launch |
| Language | `ko` | UI language (ko/en) |
| Kiro config dir | (empty) | Custom config directory |

### Project Level (Per-project)

Override model, MCP config path, and auto-start per project.

## Features

- **Chat UI** — JCEF-based chat interface, multi-session tabs, Markdown/code highlighting
- **IDE Context** — Send editor selections, insert `@File#Line` references
- **Diff Viewer** — Review file changes proposed by Kiro using IDE's built-in diff viewer
- **MCP Server Management** — View, add, remove, toggle MCP servers from mcp.json
- **Skills & Agent Management** — Browse tools, switch or create agents
- **Multi-language** — Korean and English
- **Multi-project** — Independent sessions and settings per project

## Development

JDK 21+ is required for building.

### Build

```bash
./gradlew build        # Full build
./gradlew test         # Run tests
./gradlew runIde       # Run sandbox IDE
```

### Debugging

```bash
./gradlew runIde --debug-jvm
# Connect Remote JVM Debug (localhost:5005) from IDE
```

### Test with Other IDEs

```bash
./gradlew runIde -PalternativeIdePath=/Applications/PyCharm.app/Contents
```

## Project Structure

```
src/main/kotlin/com/kiro/intellij/
├── actions/           # IDE actions (Open, SendSelection, InsertFileRef)
├── chat/              # Chat core (ChatPanel, ChatSession, KiroCliProcess)
├── diff/              # Diff viewer integration
├── mcp/               # MCP server (TCP + stdio bridge)
├── settings/          # App/project level settings
├── toolwindow/        # Tool Window UI (Manage, MCP, Skills, Agent panels)
└── util/              # Utilities (Debouncer, ExpiringCache)
```

## Architecture

```
┌──────────────────────────────────────────┐
│   JetBrains IDE                          │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │  Kiro Tool Window                   │ │
│  │  ├── Chat Tab (JCEF webview)        │ │
│  │  │   ├── ChatPanel → ChatSession    │ │
│  │  │   └── ChatBackendServer (HTTP)   │ │
│  │  └── Manage Tab                     │ │
│  │      ├── Auth / Settings / MCP      │ │
│  │      └── Skills / Agent             │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │  MCP Server (TCP + Node bridge)     │ │
│  │  → Exposes IDE tools to kiro-cli    │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │  kiro-cli (invoked per message)     │ │
│  │  → --no-interactive --resume        │ │
│  └─────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

## License

MIT License
