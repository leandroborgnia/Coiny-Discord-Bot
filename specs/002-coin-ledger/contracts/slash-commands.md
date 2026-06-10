# Contract: Discord Slash Commands (Coin Economy)

This feature adds three slash commands. All handlers implement `SlashCommandHandler`
(`bot.discord.command`), are routed by the existing `InteractionRouter`, and are registered
per-guild by the existing `SlashCommandRegistrar` (the 001 mechanism — no changes). Every handler
MUST call `event.deferReply()` **before any work** (Constitution V) and contains **no business
logic**: it parses the interaction into a request record, delegates to a `bot.application.coin`
service, and renders the result or a caught `DomainException`.

`default_member_permissions` is set per command for **visibility** only; the **authoritative**
authorization is the in-service configured-role / admin check (a member who somehow invokes a
command they are not authorized for receives a clear refusal and nothing changes).

## Command: `/balance`

| Property | Value |
|----------|-------|
| Name | `balance` |
| Description | `Show your coin balance and recent history in this server.` |
| Options | none |
| Default permissions | open to all members |
| Scope | Guild command, registered to every server the bot is in |

### Behavioral contract

1. Defer first. Delegate to `CoinQueryService.viewBalance(new ViewBalanceRequest(guildId,
   callerId, historyLimit))`, where `historyLimit` is read from the configuration property
   `coin.history.default-limit` (default `10`) — the single source of truth for the recent-history
   size.
2. Render the caller's **own** balance (an integer) and the configured cap, plus the most recent
   movements (up to `coin.history.default-limit`, default 10) newest-first, each showing direction,
   amount, any forfeiture, the reason, and the timestamp.
3. A caller with no history sees a balance of `0` and an empty history — no error.
4. The reply is **ephemeral** (only the caller sees their balance/history); a member can only
   view their own (FR-012/013/022).

*Satisfies*: US2 acceptance scenarios 1–4; FR-012, FR-013, FR-020, FR-022, FR-023.

## Command: `/coins-adjust`

A moderator grants or deducts coins. Two subcommands share the moderator permission tier.

| Property | Value |
|----------|-------|
| Name | `coins-adjust` |
| Description | `Grant or deduct a member's coins (moderator action).` |
| Default permissions | `MANAGE_SERVER` (visibility gate) |
| Scope | Guild command, registered to every server the bot is in |

| Subcommand | Options |
|------------|---------|
| `grant` | `member` (User, required), `amount` (Integer, required, ≥ 1), `reason` (String, optional) |
| `deduct` | `member` (User, required), `amount` (Integer, required, ≥ 1), `reason` (String, optional) |

### Behavioral contract

1. Defer first (ephemeral reply). Parse the subcommand → `AdjustmentType` (`grant`→`GRANT`,
   `deduct`→`DEDUCTION`), the target member, amount, and reason. Collect the **caller's role ids**
   and **isAdministrator** flag from the interaction `Member`.
2. Delegate to `AdjustCoinsService.adjust(new AdjustCoinsRequest(guildId, callerId, callerRoleIds,
   callerIsAdmin, targetMemberId, type, amount, reason, interactionId))`. The `interactionId` is
   `event.getInteraction().getIdLong()` (the at-most-once key).
3. Render from the result / exception:
   - `outcome = APPLIED` → success, e.g. `🪙 Granted 20 to @user (30 forfeited over the cap). New
     balance: 100/100.` or `🪙 Deducted 20 from @user. New balance: 30/100.`
   - `outcome = DUPLICATE` → idempotent acknowledgement, e.g. `Already applied — no change.`
   - `OverdrawException` → `❌ That would overdraw @user (balance 30). Nothing changed.`
   - `ModeratorNotAuthorizedException` → `❌ You don't have this server's coin-moderator role.`
   - `ModeratorRoleNotConfiguredException` → `❌ No coin-moderator role is configured. An admin
     must run /coins-config first.`
   - `NonPositiveAmountException` → `❌ Amount must be a whole number of at least 1.`
4. The reply MUST reflect a real, committed ledger write (a success message is only shown when the
   movement was actually appended). Failures change nothing (atomic rollback).

*Satisfies*: US1 acceptance scenarios 1–7; FR-002, FR-003, FR-007–FR-011a, FR-014–FR-019, FR-021.

## Command: `/coins-config`

Server administrators configure the moderator role and/or the cap.

| Property | Value |
|----------|-------|
| Name | `coins-config` |
| Description | `Configure the coin-moderator role and balance cap for this server.` |
| Default permissions | `ADMINISTRATOR` (visibility gate) |
| Options | `role` (Role, optional), `cap` (Integer, optional, ≥ 0) |
| Scope | Guild command, registered to every server the bot is in |

### Behavioral contract

1. Defer first (ephemeral). Require at least one of `role` / `cap`; otherwise reply asking for a
   value to set.
2. Delegate to `CoinConfigService.configure(new ConfigureCoinsRequest(guildId, callerIsAdmin,
   roleId?, cap?))`. Omitted options leave that setting unchanged.
3. Render the resulting effective config, e.g. `✅ Coin-moderator role: @Mods · cap: 50.`
4. Authoritative authorization: the service requires `callerIsAdmin`; a non-admin caller is
   refused (`ModeratorNotAuthorizedException`).

*Satisfies*: FR-007 (configurable cap), FR-011a (designate the authorizing role), and the
"no moderator role configured yet → fails closed" edge case (until this command sets a role,
`/coins-adjust` refuses).

## Registration contract

- Adding these commands means adding three `SlashCommandHandler` beans — no change to
  `InteractionRouter` or `SlashCommandRegistrar` wiring (per 001's design, new beans are picked up
  and upserted to every guild on ready / join).
- All replies are **ephemeral** by default (coin balances and moderation actions are not broadcast
  to the channel).
- Each interaction is server-scoped: the reply lands in the originating guild, and all reads/writes
  are keyed by that guild id (FR-023).
