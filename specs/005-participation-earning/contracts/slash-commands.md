# Contract — Slash Commands: `/participation-config`

One guild-only slash command with four subcommands. Authorization is the server's **configured coin
moderator role** (the economy-admin role), checked authoritatively **in-service** by
`ConfigureParticipationService` — mirroring `/coins-adjust`, because a per-guild configured role
cannot be expressed as a static Discord `DefaultMemberPermissions`. The Administrator permission is an
accepted bypass (matching `AdjustCoinsService`). Every subcommand `deferReply(true)` first
(ephemeral), then delegates (Principle V). Earning itself has **no** command — it is the background
sweep.

There is **no** member-facing earning command: members see participation in `/balance` history (US3).

## Command: `participation-config`

- `setGuildOnly(true)`.
- `DefaultMemberPermissions`: left at the default (visible) like `/coins-adjust`; the real check is
  in-service. (Optionally `enabledFor(MANAGE_SERVER)` as a coarse pre-filter — but the in-service
  moderator-role check is authoritative and must not be weakened to Manage-Server.)

### Subcommand `channel-add` (US2 / FR-012)

- Options: `channel` (`OptionType.CHANNEL`, required, `setChannelTypes(ChannelType.VOICE,
  ChannelType.STAGE)`).
- Effect: add the channel to the guild's designated set (idempotent).
- Reply: `participation.reply.channel-added` (names the channel + current count).

### Subcommand `channel-reset` (US2 / FR-013)

- Options: none.
- Effect: delete all designated channels for the guild (reset-to-none).
- Reply: `participation.reply.channel-reset`.

### Subcommand `rate` (FR-016)

- Options: `minutes-per-drop` (`INTEGER`, required, `setMinValue(1)`), `coins-per-drop` (`INTEGER`,
  required, `setMinValue(1)`).
- Effect: upsert the guild's `(minutes_per_drop, coins_per_drop)`.
- Reply: `participation.reply.rate-set` (echoes the new rate, e.g. "1 coin per 60 min").

### Subcommand `free-proposal` (US4 / FR-017)

- Options: `enabled` (`OptionType.BOOLEAN`, required).
- Effect: set `free_first_proposal` for the guild.
- Reply: `participation.reply.free-proposal-set` (on/off).

## Handler → request mapping (`ParticipationConfigCommand`)

For every subcommand the handler captures, like `AdjustCoinsCommand`:

- `guildId = event.getGuild().getIdLong()`
- `actorRoleIds = member.getRoles() → idLong set`
- `actorIsAdmin = member.hasPermission(Permission.ADMINISTRATOR)`

and builds a `ConfigureParticipationRequest` (see
[application-services.md](./application-services.md)). On `DomainException` it renders
`messages.error(e)` (e.g. not-authorized, role-not-configured).

## Error replies (i18n keys, added to `coin-messages.properties`)

| Key | When |
|-----|------|
| `coin.error.not-authorized` (reuse) | caller lacks the moderator role and is not Administrator |
| `coin.error.role-not-configured` (reuse) | no moderator role configured (fails closed) |
| `participation.reply.channel-added` / `channel-reset` / `rate-set` / `free-proposal-set` | success replies |
| `participation.reply.history.label` *(rendered in `/balance`, US3)* | participation credit line — see [application-services.md](./application-services.md) |

> The `participation.*` reply keys live in the existing `coin-messages` bundle (already on
> `spring.messages.basename`), so no new bundle/`application.yml` `basename` edit is required; a
> dedicated `ParticipationMessages` accessor is optional (the handler may reuse `CoinMessages`).
