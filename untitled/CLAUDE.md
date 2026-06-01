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
  - Chat model (default `glm-5.1`) — daily conversation, persona
  - Agent model (default `gemini-3-flash`) — tool calling, task execution
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

### Dual LLM model (BaiLianService)

Two separate model configs (API key, base URL, model name) are loaded from `BotConfig`:
- **Chat config** (`bailian.*` prefix) — used for daily conversation with the "糖果熊" persona
- **Agent config** (`agent.*` prefix) — used for tool calling, user portrait analysis, game logic

Both use the same OpenAI-compatible chat completions API via `java.net.http.HttpClient`.

### Agent tool calling

`Tool` interface defines `getName()`, `getDescription()`, `getParameters()` (JSON Schema), and `execute(Map<String, Object> args)`. Tools are registered in `BaiLianService`'s constructor. The LLM is prompted to output XML-style `<tool_call>` blocks; `BaiLianService` parses these, executes the matching tool, and feeds results back to the LLM for a final response.

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
