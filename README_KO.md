# Kiro with GUI — IntelliJ Plugin

한국어 | [English](README.md)

[Kiro CLI](https://kiro.dev)를 JetBrains IDE에 통합하는 GUI 플러그인입니다.
IDE의 Tool Window 안에서 kiro-cli와 대화하고, diff viewer, 에디터 컨텍스트, MCP 서버 등 IDE 기능과 연동할 수 있습니다.

## 요구 사항

| 항목 | 버전 |
|------|------|
| JetBrains IDE | 2025.1+ (IntelliJ IDEA, PyCharm, WebStorm 등) |
| kiro-cli | 설치 필요 ([설치 가이드](https://kiro.dev/docs/cli/)) |
| JCEF | 채팅 UI는 IDE 내장 Chromium(기본 번들)을 사용합니다. Linux에서는 glibc 2.28 이상이 필요하며, **Amazon Linux 2 등 오래된 배포판은 지원되지 않습니다** (우회: Remote Development + 로컬 JetBrains Client) |

## 설치

### Marketplace에서 설치

1. IDE에서 **Settings → Plugins → Marketplace**
2. "Kiro with GUI" 검색
3. Install → IDE 재시작

### 로컬 빌드 설치

```bash
git clone https://github.com/AwesomeHye/Intellij-Kiro-with-GUI.git
cd intellij-kiro-plugin
./gradlew buildPlugin
# 빌드 결과: build/distributions/kiro-with-gui-0.1.0.zip
```

1. IDE에서 **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
2. 빌드된 zip 파일 선택
3. IDE 재시작

## 사용법

### 채팅

1. IDE 우측의 **Kiro** Tool Window 클릭 (또는 `Cmd+Esc`)
2. Chat 탭에서 메시지 입력
3. 모델 선택: 입력창 하단의 모델 버튼 클릭

### 단축키

| 단축키 (Mac) | 동작 |
|-------------|------|
| `Cmd+Esc` | Kiro Tool Window 열기/포커스 |
| `Cmd+Shift+K` | 선택 텍스트를 Kiro에 전송 |
| `Cmd+Option+K` | 현재 파일 참조 삽입 |

### 슬래시 커맨드

| 커맨드 | 설명 |
|--------|------|
| `/model [이름]` | 현재 모델 확인/변경 |
| `/clear` | 대화 기록 초기화 |
| `/status` | CLI 상태 확인 (경로, 버전, 인증) |
| `/help` | 도움말 |
| `/context` | 컨텍스트 파일 관리 (CLI 전달) |
| `/tools` | 도구 및 권한 보기 (CLI 전달) |
| `/mcp` | MCP 서버 목록 (CLI 전달) |
| `/compact` | 대화 요약 (CLI 전달) |

### 관리 탭

Tool Window의 **Manage** 탭에서:

- **인증** — 로그인/로그아웃 상태 관리
- **설정** — kiro-cli 경로, 기본 모델, 언어, Kiro 설정 디렉토리
- **MCP** — MCP 서버 목록, 추가/제거/편집
- **Skills** — 사용 가능한 도구 목록 및 검색
- **Agent** — 에이전트 목록, 전환, 생성/편집

## 설정

### 앱 레벨 (전체 IDE)

**Settings → Tools → Kiro** 또는 Manage 탭 → 설정

| 설정 | 기본값 | 설명 |
|------|--------|------|
| Kiro command | `kiro-cli` | kiro-cli 실행 경로 |
| Default model | `Auto` | 기본 모델 |
| Auto-start | `true` | IDE 시작 시 자동 실행 |
| Language | `ko` | UI 언어 (ko/en) |
| Kiro config dir | (비어있음) | 커스텀 설정 디렉토리 |

### 프로젝트 레벨 (프로젝트별 독립)

프로젝트별로 모델, MCP 설정 경로, 자동 시작 여부를 오버라이드할 수 있습니다.

## 주요 기능

- **채팅 UI** — JCEF 기반 채팅 인터페이스, 멀티 세션 탭, Markdown/코드 하이라이팅
- **IDE 컨텍스트 연동** — 에디터 선택 텍스트 전송, `@파일#라인` 참조 삽입
- **Diff Viewer** — kiro-cli가 제안한 파일 수정을 IDE diff viewer로 표시
- **MCP 서버 관리** — mcp.json 기반 서버 목록 확인, 추가/제거, 활성화/비활성화
- **Skills & Agent 관리** — 도구 목록, 에이전트 전환/생성
- **다국어 지원** — 한국어/영어
- **다중 프로젝트** — 프로젝트별 독립 세션과 설정

## 개발

빌드에는 JDK 21+가 필요합니다.

### 빌드

```bash
./gradlew build        # 전체 빌드
./gradlew test         # 테스트 실행
./gradlew runIde       # Sandbox IDE 실행
```

### 디버깅

```bash
./gradlew runIde --debug-jvm
# IDE에서 Remote JVM Debug (localhost:5005) 연결
```

### 다른 IDE에서 테스트

```bash
./gradlew runIde -PalternativeIdePath=/Applications/PyCharm.app/Contents
```

## 프로젝트 구조

```
src/main/kotlin/com/kiro/intellij/
├── actions/           # IDE 액션 (Open, SendSelection, InsertFileRef)
├── chat/              # 채팅 코어 (ChatPanel, ChatSession, KiroCliProcess)
├── diff/              # Diff viewer 연동
├── mcp/               # MCP 서버 (TCP + stdio bridge)
├── settings/          # 앱/프로젝트 레벨 설정
├── toolwindow/        # Tool Window UI (Manage, MCP, Skills, Agent 패널)
└── util/              # 유틸리티 (Debouncer, ExpiringCache)
```

## 아키텍처

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
│  │  → kiro-cli에 IDE 도구 노출         │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │  kiro-cli (매 메시지마다 실행)       │ │
│  │  → --no-interactive --resume        │ │
│  └─────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

## 라이선스

MIT License
