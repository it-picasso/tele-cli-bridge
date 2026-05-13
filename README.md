# tele-cli-bridge

A Spring Boot 4.0 / Java 25 Telegram long-polling bot that fronts the locally installed **`claude`** and **`gemini`** CLIs. Each user message is shelled out to one of the two CLIs as a subprocess; stdout is sent back to the chat.

The CLIs handle their own authentication outside the JVM, so this app holds no API keys.

## Highlights

- **Two providers, one bot.** Per-chat conversation is routed to either `claude` or `gemini`. The active model is selectable via the admin-only `/model` inline keyboard.
- **Group + DM aware.** Works in private chats and Telegram groups. In groups the bot only responds to `@<bot-username>` mentions, configured trigger phrases, or Telegram replies to its own messages.
- **Sandboxed subprocesses.** Every CLI invocation runs in an isolated tmp directory whose only project memory is the security-instructions file (`CLAUDE.md` / `GEMINI.md`). User input is passed via `ProcessBuilder` argv (no shell). Read-only modes are forced (`--permission-mode plan` for Claude, `--approval-mode plan` for Gemini), built-in tools are disabled (`--tools ""` for Claude), and the per-CLI persona is instructed to inline any requested file content in chat rather than writing to the host filesystem.
- **Replies in the user's language.** Both CLI security profiles instruct the model to reply in whatever language the user wrote in (English fallback if undetectable).

## Prerequisites

- **JDK 25**. If your default `java` isn't 25, set `JAVA_HOME` either in your shell or in `.env` (the bundled `run.sh` reads it from `.env` and prefixes `$JAVA_HOME/bin` to `PATH` for both the Maven build and the JVM launch).
- **`claude` CLI** on `PATH`, authenticated. Verify: `claude --version` then `claude -p "ping"`.
- **`gemini` CLI** on `PATH`, authenticated. Verify: `gemini --version` then `gemini -p "ping" -o text`.
- A Telegram bot registered with [@BotFather](https://t.me/BotFather), giving you `BOT_NAME` (the @username; `@` prefix optional) and `BOT_TOKEN`.

## Quick start

1. `cd` into the project root.
2. Copy `env.example` to `.env` at the project root and fill in `BOT_NAME` and `BOT_TOKEN`. Uncomment any other variables you want to override (`JAVA_HOME`, allowed users/groups, models, effort level, etc.). LF line endings only — CRLF will leak `\r` into env vars and break Telegram auth.
   ```bash
   cp env.example .env && $EDITOR .env
   ```
3. Build and run with the bundled script (kills any running instance first, builds, launches in background, tails `bot.log` until the startup line appears):
   ```bash
   ./run.sh
   ```
   Equivalent manual steps if you'd rather run them yourself (export `JAVA_HOME` first if your default `java` isn't 25):
   ```bash
   mvn -s ./maven-settings.xml clean package
   set -a; . ./.env; set +a; LANG=C.UTF-8 LC_ALL=C.UTF-8 \
     java -jar target/tele-cli-bridge-0.0.1.jar
   ```
   `LANG=C.UTF-8` is required so non-ASCII characters in user messages — e.g. cyrillic — survive the JVM-to-subprocess argv encoding.
4. DM your bot in Telegram. It should reply.

## Configuration

Every value in `src/main/resources/application.properties` is wrapped as `${ENV_VAR:default}`, so any property can be **overridden by an environment variable at launch time without rebuilding**. `run.sh` sources `.env` before launching, so the simplest workflow is to put overrides in `.env`. See `env.example` for the full list of supported variables and their defaults. The tables below give the corresponding properties for reference.

`application.properties` is committed (now that values come from env vars, the file holds only defaults — no secrets or per-user data). Secrets stay in `.env`, which is gitignored.

### Telegram access

| Env var | Property | Default | Meaning |
|---|---|---|---|
| `BOT_NAME` | `pro.xpst.telegram.bot.username` | **required** | Bot @username (with or without leading `@`). |
| `BOT_TOKEN` | `pro.xpst.telegram.bot.token` | **required** | Bot token from @BotFather. |
| `BOT_USERS_ALLOWED` | `pro.xpst.telegram.bot.users.allowed` | empty (= all) | Comma-separated Telegram user IDs that may **DM** the bot. Does not affect group access. |
| `BOT_USERS_ADMIN` | `pro.xpst.telegram.bot.users.admin` | empty (= no one) | Comma-separated Telegram user IDs that may use `/model`. Empty = `/model` is locked. |
| `BOT_GROUPS_ALLOWED` | `pro.xpst.telegram.bot.groups.allowed` | empty (= all) | Comma-separated group/supergroup chat IDs (negative numbers). **Anyone in an allowed group** can talk to the bot — the group itself is the trust boundary. |
| `BOT_TRIGGER_PHRASES` | `pro.xpst.telegram.bot.trigger.phrases` | empty | Comma-separated, case-insensitive phrases that activate the bot **in groups only**. Empty = require `@mention`. |
| `BOT_SKIP_STALE_ON_STARTUP` | `pro.xpst.telegram.bot.skip-stale-on-startup` | `true` | When `true`, messages and `/model` callbacks queued by Telegram while the bot was offline are dropped silently on restart. Set to `false` to process the backlog (e.g. for debugging). |
| `BOT_STARTUP_GRACE_SECONDS` | `pro.xpst.telegram.bot.startup-grace-seconds` | `0` | Seconds of leniency before startup that still count as "fresh". Raise if you expect clock skew between Telegram's servers and the host. |

### Models

| Env var | Property | Default | Meaning |
|---|---|---|---|
| `DEFAULT_PROVIDER` | `pro.xpst.default.provider` | `claude` | Provider for the first message in a brand-new chat (`claude` or `gemini`). |
| `CLAUDE_DEFAULT_MODEL` | `pro.xpst.claude.default-model` | `claude-haiku-4-5` | Initial model for new Claude-backed chats. |
| `GEMINI_DEFAULT_MODEL` | `pro.xpst.gemini.default-model` | `gemini-2.5-flash-lite` | Initial model for new Gemini-backed chats. |
| `CLAUDE_MODELS` | `pro.xpst.claude.models` | `claude-sonnet-4-6,claude-haiku-4-5` | Allowlist of Claude models shown in the `/model` keyboard. |
| `GEMINI_MODELS` | `pro.xpst.gemini.models` | `gemini-2.5-flash,gemini-2.5-flash-lite` | Allowlist of Gemini models shown in the `/model` keyboard. |
| `CLAUDE_EFFORT` | `pro.xpst.cli.claude.effort` | `low` | Reasoning effort passed to Claude via `--effort`. Values: `low | medium | high | xhigh | max`, or empty for CLI default. |

### CLI invocation

| Env var | Property | Default | Meaning |
|---|---|---|---|
| `CLAUDE_BINARY` | `pro.xpst.cli.claude.binary` | `claude` | Override if not on `PATH`. |
| `GEMINI_BINARY` | `pro.xpst.cli.gemini.binary` | `gemini` | Override if not on `PATH`. |
| `CLAUDE_WORKDIR` | `pro.xpst.cli.claude.workdir` | `./claude-cli` | Source directory for `CLAUDE.md` (security instructions). At startup the file is copied into a fresh tmp sandbox dir which becomes the subprocess `cwd`. |
| `GEMINI_WORKDIR` | `pro.xpst.cli.gemini.workdir` | `./gemini-cli` | Same arrangement for `GEMINI.md`. |
| `CLI_TIMEOUT_SECONDS` | `pro.xpst.cli.timeout-seconds` | `120` | Per-call subprocess timeout. Times out → `destroyForcibly()`. |
| `CLAUDE_TOOLS` | `pro.xpst.cli.claude.tools` | empty | Comma-separated allowlist of Claude built-in tools (e.g. `WebSearch`, `WebSearch,WebFetch`). Empty = strict lockdown (`--tools ""`). See **Security model** before enabling. |
| `GEMINI_TOOLS` | `pro.xpst.cli.gemini.tools` | empty | Comma-separated allowlist of Gemini built-in tools (e.g. `google_web_search`). Empty = strict lockdown (no `--allowed-tools` flag passed). See **Security model** before enabling. |
| `CONVERSATION_EXPIRATION` | `pro.xpst.conversation.expiration` | `60` | Minutes of inactivity after which a chat's history auto-resets. |
| `SERVER_PORT` | `server.port` | `8191` | Spring Boot HTTP port (Tomcat is brought up by `spring-boot-starter-web`; the bot itself is long-polling). |
| `LOG_LEVEL` | `logging.level.pro.xpst` | `DEBUG` | Log level for the `pro.xpst` package. |

## Group-chat usage

In **private** chats, every message from an `users.allowed` user is sent to the CLI.

In **groups** the bot is silent unless **one** of the following is true:
- the message contains `@<bot-username>`,
- the message contains a phrase listed in `pro.xpst.telegram.bot.trigger.phrases` (case-insensitive), **or**
- the message is a Telegram reply to one of the bot's own messages.

The matched mention or trigger is stripped (along with adjacent punctuation) before the prompt is forwarded to the CLI. Reply-to-bot messages are passed through unstripped — the full reply text becomes the prompt. The slash command `/model@<bot-username>` works in groups normally; only admins (per `users.admin`) can issue it.

## Restart behavior

Telegram queues updates server-side while a long-polling bot is offline. On restart, by default the bot drops every queued message and `/model` callback whose `Message.date` predates the JVM's startup — answering hours-old questions is rarely what the user wants. The skipped updates are logged at `DEBUG` (`Skipping stale message …` / `Skipping stale callback query …`). Disable via `BOT_SKIP_STALE_ON_STARTUP=false`, or widen the freshness window with `BOT_STARTUP_GRACE_SECONDS=<n>`.

## Security model

The bot's threat model assumes Telegram users may send hostile content (prompt injection, attempted shell escapes, etc.). Defenses, in layers:

1. **Subprocess argv form.** `ProcessBuilder` is invoked with `[binary, "--model", model, …, "-p", prompt]` as a list — no shell interpolation. Backticks, `$(...)`, `;rm -rf`, etc. are passed as literal text.
2. **Sandboxed `cwd`.** Every CLI invocation runs from a fresh `/tmp/tele-cli-bridge-cli-*/` dir whose only project memory is the security file copied in at startup. Parent CLAUDE.md / GEMINI.md from the project tree cannot leak into the model's context.
3. **Read-only CLI permission modes.**
   - **Claude `--permission-mode plan`** — puts Claude into plan mode, where the model may analyse and propose but cannot execute any state-changing tool action. No file writes, no shell, no MCP side-effects.
   - **Claude `--tools ""`** — disables every built-in tool (Bash, Read, Write, Edit, WebFetch, WebSearch, Task, etc.). Defense-in-depth on top of plan mode: even if the model attempts a tool, the empty allowlist refuses it.
   - **Gemini `--approval-mode plan`** — Gemini's equivalent of plan mode: read-only session, all destructive operations are blocked. (`--skip-trust` is also passed because the sandbox tmp dir isn't pre-trusted and Gemini would otherwise refuse to start in plan mode.)
4. **System-prompt injection (Claude).** The contents of `claude-cli/CLAUDE.md` are passed via `--system-prompt`, replacing Claude Code's default coding-assistant persona with a plain chat-assistant persona that follows the security rules.
5. **Per-CLI memory (Gemini).** `gemini-cli/GEMINI.md` is auto-loaded by the CLI from the sandbox `cwd` and instructs the model the same way (no tool use, no project context, no persona leak, never write files on the host — inline requested file content in chat instead, language matching).
6. **Admin-gated model switching.** Only IDs in `pro.xpst.telegram.bot.users.admin` can use `/model`; non-admin clicks on the inline keyboard are silently dropped.
7. **Allow-listed users and groups.** Empty list for users/groups means "everyone" (convenient for dev); set both lists in production.
8. **Optional tool allowlist.** `CLAUDE_TOOLS` / `GEMINI_TOOLS` enable named built-in tools (e.g. `WebSearch`, `google_web_search`) — both empty by default. When non-empty, the per-CLI security file is augmented at runtime with a "Tool authorization" clause that names the allowed tools and keeps every other rule (no filesystem, no shell, refuse jailbreak, language match) in force. The committed security files stay strict; only the runtime-augmented copy is sent to the CLI. **Permission-mode change for Claude:** when `CLAUDE_TOOLS` is non-empty, Claude's `--permission-mode plan` is dropped and the argv switches to `--tools <list> --allowed-tools <list>` — `plan` mode otherwise refuses external-network tools like `WebSearch` even when listed. The `--tools <list>` allowlist becomes the safety floor (anything outside the list is rejected at the CLI layer). Gemini keeps `--approval-mode plan` either way; its plan mode does not block read-only built-ins like `google_web_search`. Trade-offs to weigh before enabling:
   - **Prompt injection from search results.** A malicious page in the result set can try to subvert the model. The override clause keeps the rest of the rules in force, but doesn't fully neutralize this.
   - **Search-query disclosure.** User text becomes a search query, so anything sensitive in messages reaches the search provider.
   - **`WebFetch` / `web_fetch` is riskier than search.** It lets the model fetch arbitrary URLs — potential SSRF if the host has reachable internal services. Recommend off unless explicitly needed.

## Not included

- No tests. `mvn test` is a no-op — there is no `src/test/...` tree.
- No Docker. The CLIs persist auth under the user's home directory, so containerization is fiddly. Run on a host where both CLIs are already authenticated.

## License

See `LICENSE`.
