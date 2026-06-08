# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

称呼用户为"归儿"。

## Build & Deploy

```bash
# Build fat JAR (skips tests)
mvn clean package -DskipTests
# Output: target/untitled-1.0-SNAPSHOT.jar

# Full deploy to remote server (builds, uploads, restarts via screen)
./deploy.sh
```

No test suite is configured — `mvn test` has no runnable tests currently.

## Tech Stack

- **Java 17**, Maven with shade plugin (fat JAR), no Spring/DI framework
- **WebSocket client** (`org.java-websocket:Java-WebSocket`) connects to NapCat via OneBot v11 protocol
- **MySQL** via HikariCP connection pool, hand-written JDBC in repository classes
- **Two LLM backends** via OpenAI-compatible HTTP APIs:
  - Chat model (default `glm-5.1`) — **当前唯一活跃路径**：日常对话 + OpenAI 原生 function calling 工具调用
  - Agent model (default `gemini-3-flash`) — 暂时不使用（Agent 链路暂停）
- **HanLP** (portable) for Chinese NLP / keyword extraction
- **Selenium + WebDriverManager** for webpage screenshots
- **Java2D** for image generation (CP cards, profession cards) with HarmonyOS Sans fonts

## Architecture

### Message dispatch: Chain of Responsibility

`HandlerRegistry` holds an ordered list of `MessageHandler` implementations. On each incoming message, it iterates and dispatches to the **first handler whose `match()` returns true**. Order matters — `AIHandler` is the catch-all (its `match()` returns true for all group messages) and must be last.

Priority order (top to bottom):
1. HelloHandler → 2. LuckHandler → 3. JokeHandler → 4. ReminderHandler → 5. SanjiaoHandler → 6. DailyProfessionHandler → 7. DailyCpHandler → 8. RankHandler → 9. EggGroupSearchHandler → 10. AgentHandler ("请帮我...") → 11. TravelingMerchantHandler → 12. **AIHandler (fallback)**

### OneBot async communication

`Main.callOneBotApi()` sends a JSON request over WebSocket with a random `echo` field, then puts a `CompletableFuture` into a `ConcurrentHashMap`. When the response arrives in `onMessage()`, the `echo` value is used to complete the matching future. This converts the async WebSocket into a request-response style.

### 消息处理链路

目前 **Agent 链路（AgentHandler → AgentService → `BaiLianService.generateWithTools()`）暂时不使用**。所有消息最终由 **AI 聊天链路**（AIHandler → `BaiLianService.generate()`）兜底处理，该链路已内置完整的 OpenAI 原生 function calling 工具体系（见下方）。

### AI 聊天链路（唯一活跃路径）

`BaiLianService.generate()` 使用 Chat 模型（`bailian.*` 前缀配置），**带完整的 OpenAI 原生 function calling**：
- 系统提示包含 28 个工具的详细使用说明（第 398-679 行）
- 每轮请求都携带 `tools` 参数，`tool_choice: auto`
- 最多 6 轮工具调用循环（第 901-1000 行）
- `Tool` 接口：`getName()`, `getDescription()`, `getParameters()` (JSON Schema), `execute(Map<String, Object> args)`

关键工具包括 `query_life`（查糖果熊自己的连续生活状态）、`search_chat_history`、`recall_memory`、`remember_fact`、`query_knowledge` 等。

### 糖果熊生命引擎（CandyBearLifeEngine）

四层架构保证生命连续性：

| 层 | 生成频率 | 存储表 |
|---|---|---|
| LifeState | 随 StoryArc 演进更新 | `candy_bear_life_state`（单行） |
| StoryArc | 2~3 周章节，到期前 3 天自动续 | `candy_bear_story_arcs` |
| WeeklyDiary | 每周日生成 | `candy_bear_weekly_diaries` |
| DailyJournal | 每天凌晨 3 点生成昨日日记 | `candy_bear_daily_journals` |
| Schedule | 每周日生成下周日程（7天×5时段） | `candy_bear_schedule` |

- `queryLifeContext()` 供 `QueryLifeTool` 调用，返回章节 + 最近 7 天日记 + 本周计划 + 今日日程 + 当前时刻活动
- 上下层之间引用保证连续性（日记引用前天日记+当前章节；周记引用本周日记；章节引用最近日记）
- LifeState 从 DB 读取动态拼接人设 prompt，DB 不可用时 fallback 到硬编码默认值
- Schedule 生成后注入日记生成 prompt 作为 grounding

### Repository pattern

All DB access goes through classes extending `BaseRepository`, which provides `executeQuery`/`executeInsert`/`executeUpdate` template methods with connection management (HikariCP) and retry logic. SQL is hand-written; no ORM.

### Configuration

`application.properties` is loaded by `BotConfig`'s static initializer. Values support `${ENV_VAR:default}` syntax for environment variable substitution. Sensitive values (API keys, tokens) are injected via environment variables, never hardcoded.

## Key conventions

- **Manual DI**: `Main` constructor instantiates all services and wires them together. No framework.
- **Logging**: SLF4J + Logback, `com.start` at DEBUG level.
- **Thread safety**: `ConcurrentHashMap` for shared state; `ExecutorService` (fixed thread pool) for async AI calls; `ConcurrentLinkedDeque` for message history.
- **Fonts**: Image rendering depends on HarmonyOS Sans fonts in `src/main/resources/assets/fonts/`. The code loads them via classpath, so they must be present in the JAR.
- **Remote deploy**: The `deploy.sh` script expects SSH key at `~/.ssh/id_ed25519`, server `154.8.213.134`, and a `.env` file at `/opt/qq-bot/.env` on the server.
