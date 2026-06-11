# Contract: Slash Commands & Upvote Button (Game Queue)

Thin JDA handlers (Principle V): **defer/ack first**, parse the interaction, read the member's
in-memory cached presence where needed, delegate to one `bot.application.queue` service, render.
All user-facing copy resolves through `QueueMessages` against `messages/queue-messages.properties`.
Every command is `setGuildOnly(true)`. Replies are **ephemeral** unless noted.

## `/queue-propose` → `ProposeGameService.propose` (US1, P1)
Ephemeral. No options — the game is captured from Rich Presence.

- `deferReply(true)` → read `event.getMember().getActivities()`, take the first `PLAYING` activity →
  build `CapturedGame` (null if none). Build `ProposeGameRequest(guildId, memberId, capturedGame,
  interactionId)`; call the service; render.
- Renders: proposed-at-position N / **instant-pop** (became this week's game) / replaced-existing /
  duplicate (no-op) / **no-activity** guidance (FR-035) / insufficient coins / not-eligible (cooldown
  with games remaining).

## `/queue-bump` → `BumpGameService.bump` (US4, P4)
Ephemeral, no options (acts on the caller's own queued slot).
- `deferReply(true)` → `BumpGameRequest(guildId, memberId, interactionId)` → render.
- Renders: bumped to position N / already-at-top (the `AT_TOP` outcome, `queue.error.at-top`, not an
  exception) / no-queued-game / insufficient coins / duplicate.

## `/queue-withdraw` → `WithdrawGameService.withdraw` (US1, P1)
Ephemeral, no options.
- `deferReply(true)` → `WithdrawGameRequest(guildId, memberId, interactionId)` → render.
- Renders: withdrawn + refunded X coins / no-queued-game / duplicate.

## `/queue-view` → `ViewQueueService.view` (US3, P3)
**Ephemeral** (FR-028). No options.
- `deferReply(true)` → `ViewQueueRequest(guildId, memberId)` → render an embed list with the current
  game (key art), the next 5 (key art, proposer, position, **snapshot** upvote count), the viewer's
  own entry always shown/marked (even beyond top 5), and the viewer's eligibility / games-remaining.
- Upvote **buttons** are attached per shown slot: `componentId = "upvote:{slotId}"`. Pressing them
  does **not** re-render this ephemeral message (counts stay a snapshot, FR-029).
- Empty state renders "no current game, queue empty".

## `/queue-config` → `ConfigureQueueService.configure` (config, authorized)
Ephemeral, **Manage Server**-gated. `DefaultMemberPermissions.enabledFor(MANAGE_SERVER)` is both the
Discord-layer filter **and** the authoritative bar: the handler passes `actorHasManageServer =
member.hasPermission(Permission.MANAGE_SERVER)` and the service authorizes on exactly that — so the
two gates never disagree. Manage Server (not Administrator/owner) is the intended bar: tuning queue
costs and the announcement channel is an operational server-staff action, lighter than `/coins-config`
(which stays Administrator because it governs who controls the economy). No pre-configured role is
required, so there is no role-not-configured path here.
Subcommands:
- `costs propose:<int≥1> bump:<int≥1>` (both optional; null leaves unchanged).
- `announce channel:<channel>` (set) / `announce-clear` (clear the channel).
- Renders effective config / not-authorized.

## Upvote **button** → `UpvoteService.toggle` (US5, P4)
Routed by `ButtonInteractionRouter` to `UpvoteButtonHandler` (prefix `upvote:`).
- The button's `componentId` is **`upvote:{slotId}:{gameInstanceId}`** — it encodes the slot's
  appearance (the app-generated `game_instance_id`) the button was rendered for, so a press after the
  game was replaced is detectable as stale.
- `event.deferEdit().queue()` — acknowledges **without** changing the ephemeral message.
- Parse `slotId` and `gameInstanceId` from `componentId`; `ToggleUpvoteRequest(guildId, memberId,
  slotId, gameInstanceId, interactionId)`; call the service.
- The service result drives an **announcement edit** (FR-038) via `AnnouncementPort` only when the vote
  actually `changed` — the ephemeral stays a snapshot. A no-op toggle (duplicate across renders) or a
  `STALE` press (the slot's game was replaced — outcome `STALE`) edits nothing.

## Command registration
Each handler is a `SlashCommandHandler` bean (auto-registered by `SlashCommandRegistrar`).
`ButtonHandler` is a new interface; `ButtonInteractionRouter` collects the beans and dispatches by
`componentId` prefix, mirroring `InteractionRouter`.

## Error → copy mapping
`DomainException` subclasses resolve to keys in `queue-messages.properties`:

| Exception | Key (example) |
|-----------|---------------|
| `NoGameActivityException` | `queue.error.no-activity` |
| `InsufficientCoinsException` | `queue.error.insufficient` |
| `NotEligibleException` (cooldown) | `queue.error.cooldown` (args: games remaining) |
| `NoQueuedGameException` | `queue.error.no-queued` |
| `NotAuthorizedException` (Manage Server required) | `queue.error.not-authorized` |
