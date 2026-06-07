# Contract: Discord Slash Commands

The bot's external interface in this slice is a single slash command. This contract defines its
shape and response behavior. Handlers implement `SlashCommandHandler` (`bot.discord.command`) and
are routed by `InteractionRouter`; commands are upserted by `SlashCommandRegistrar` on JDA ready.

## Command: `/ping`

| Property | Value |
|----------|-------|
| Name | `ping` |
| Description | `Liveness check — confirms the bot is online and its data store is reachable.` |
| Options | none |
| Scope | Guild command, registered to **every server the bot is in** (instant availability) |
| Permissions | Usable by any member who can use slash commands in the server |

### Behavioral contract

1. On invocation, the handler MUST call `event.deferReply()` **before** any other work, well within
   Discord's 3s interaction-response window (target < 2.5s; in practice immediate).
2. The handler MUST NOT contain business logic; it delegates to
   `LivenessService.check(CheckLivenessRequest)` (see [application-services.md](./application-services.md)).
3. After the service returns, the handler edits the deferred reply via `event.getHook()`:
   - **Reachable** → an ephemeral or normal success message, e.g.
     `🟢 Pong! Data store reachable (ok).`
   - **Not reachable** → a clear non-success message, e.g.
     `🔴 Pong, but the data store is not reachable right now.`
4. The reply MUST reflect a real round-trip to the data store; a success message is only sent when
   the underlying probe reports `reachable = true` (spec FR-002, SC-002).
5. Each interaction is server-scoped: the reply MUST land in the same server (guild) and channel the
   command was invoked in (JDA's interaction hook does this), and the router MUST log the originating
   guild and channel. No cross-server state is shared.

### Acceptance mapping

- Satisfies spec **US1** acceptance scenarios 1–3, **FR-001**, **FR-002**, **FR-003**, and the
  "data store slow" / "data store down after healthy startup" edge cases.

## Registration contract

- On `ReadyEvent`, `SlashCommandRegistrar` collects command data from all `SlashCommandHandler`
  beans and upserts them to **every guild the bot is in** (`jda.getGuilds()`).
- On `GuildJoinEvent`, the same command set is upserted to the newly-joined guild, so servers the
  bot joins later are covered automatically.
- JDA receives guild and join events by default, so `getGuilds()` is populated and `GuildJoinEvent`
  fires without any extra intent. There is no configured single guild id.
- Re-running registration MUST be idempotent (upsert replaces each guild's command set; no
  duplicates).
- Adding a future command means adding a new `SlashCommandHandler` bean — no change to the router or
  registrar wiring.
