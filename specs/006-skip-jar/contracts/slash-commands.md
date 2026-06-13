# Contract — Slash Commands

Two commands. Both **defer first** (`event.deferReply()`), do **no** work before deferring (Principle
V), then delegate to a `bot.application.skipjar` service (request record in, result record out) and
render. Per-guild only (reject DMs).

## `/skip` — contribute & view (any member)

### `/skip contribute`

Cast a one-coin vote into the current game's skip jar (US1).

- **Options**: none (the current game and run are read server-side).
- **Delegates to**: `ContributeToSkipJarService.contribute(ContributeRequest)` with
  `(guildId, memberId, interactionId, now)`.
- **Replies** (ephemeral):
  - **Success, no skip**: "Paid 1 coin into the skip jar. {count}/{threshold} — {remaining} more to
    skip **{gameName}**." (FR-001/FR-014)
  - **Success, skip triggered**: "Paid 1 coin — the jar is full! **{retiredGame}** is retired;
    **{newGame}** is up now." (FR-010) When an announcement channel is configured, the regular
    rotation announcement is also posted/updated (reusing `AnnouncementAssembler`).
  - **Refused — already contributed** (`AlreadyContributedException`): "You've already paid into this
    game's skip jar." No charge (FR-002).
  - **Refused — not an earner, gate on** (`NotEligibleToContributeException`): "Only members who've
    earned coins from **{gameName}** can vote to skip it." (FR-004)
  - **Refused — insufficient balance** (`OverdrawException`, surfaced by the V2 non-negative trigger /
    pre-check): "You need at least 1 coin to contribute." (FR-006)
  - **Refused — jar closed (dwell)** (`JarClosedException`): "**{gameName}** can't be skipped yet —
    the jar opens {opensRelative} (after {dwell} as the week's game)." (FR-007)
  - **Refused — no current game** (`NoCurrentGameException`): "There's no current game to skip."
    (FR-019)

### `/skip status`

View the current game's skip jar (US3, FR-014). Read-only; no lock.

- **Options**: none.
- **Delegates to**: `ViewSkipJarService.view(ViewRequest)` with `(guildId, now)`.
- **Replies** (ephemeral):
  - **Open**: "Skip jar for **{gameName}**: {count}/{threshold} ({remaining} more to skip). Threshold
    = majority of {earnerCount} earners, floor {floor}."
  - **Not open yet (dwell)**: "Skip jar for **{gameName}** opens {opensRelative}." (FR-014)
  - **No game**: "There's no current game, so nothing to skip." (FR-014, no error)

## `/skip-config` — admin tuning (moderator role)

Authorized exactly like `/participation-config` and `/coins-config`: against the server's configured
coin-moderator role; Discord **Administrator bypasses**; **fails closed** when no role is configured.
Unauthorized requests change nothing (FR-017 / SC-009). Each subcommand delegates to
`ConfigureSkipJarService.configure(ConfigureRequest)` and replies with the re-read effective config.

| Subcommand | Option | Validation | Effect |
|------------|--------|-----------|--------|
| `/skip-config floor` | `value` (integer, **min 1**) | `>= 1` | set `threshold_floor` (FR-015) |
| `/skip-config dwell` | `hours` (number, **min > 0**) | `> 0` | set `dwell_seconds` = `round(hours * 3600)` (FR-016) |
| `/skip-config gate` | `enabled` (boolean) | — | set `participation_gate` (FR-005) |

- **Authorized reply**: "Skip jar updated — floor {floor}, dwell {dwell}, gate {on/off}."
- **Refused — not authorized** (`ModeratorNotAuthorizedException`): "You don't have the role to change
  skip-jar settings."
- **Refused — no role configured** (`ModeratorRoleNotConfiguredException`): "No moderator role is
  configured; set one with `/coins-config` first."

> Dwell is entered in **hours** (a friendly unit) and stored as `dwell_seconds`. The command sets the
> Discord option minimums so the service's defensive re-validation (`>= 1` floor, `> 0` dwell) is
> never normally hit.
