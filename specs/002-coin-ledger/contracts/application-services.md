# Contract: Application Services (Coin Economy)

Per CLAUDE.md, every public application-service method **takes a request record and returns a
result record**, and these services are the **only** components that open transactions and call
ports (Constitution Principle II). Rule violations are signalled with typed `DomainException`s
(never raw `RuntimeException`), which roll the transaction back so a failed operation changes
nothing.

All records are immutable Java `record`s. Ids are Discord snowflakes (`long`). Amounts are whole
(`int`).

## `AdjustCoinsService.adjust` — grant / deduct (the write path)

`@Service`, `@Transactional` (read-write). This is the only path that mutates the ledger.

**Request** — `AdjustCoinsRequest`:

| Field | Type | Notes |
|-------|------|-------|
| `guildId` | `long` | Server scope. |
| `actorMemberId` | `long` | The invoking moderator. |
| `actorRoleIds` | `Set<Long>` | The invoker's role ids (for authorization). |
| `actorIsAdmin` | `boolean` | True if the invoker has Discord Administrator. |
| `targetMemberId` | `long` | The affected member. |
| `type` | `AdjustmentType` | `GRANT` or `DEDUCTION`. |
| `amount` | `int` | Requested whole amount (`≥ 1`; `≤ 0` ⇒ `NonPositiveAmountException`). |
| `reason` | `String` (nullable) | Moderator-supplied context. |
| `interactionId` | `long` | At-most-once idempotency key. |

**Result** — `AdjustCoinsResult`:

| Field | Type | Notes |
|-------|------|-------|
| `outcome` | `Outcome` | `APPLIED` or `DUPLICATE`. |
| `newBalance` | `int` | The member's derived balance after the movement. |
| `creditedAmount` | `int` | Coins that landed (≤ requested for grants). |
| `forfeitedAmount` | `int` | Over-cap coins forfeited (grants only; else 0). |
| `cap` | `int` | The server's effective cap (for rendering). |

**Algorithm** (all within the single transaction):

1. Validate `amount ≥ 1` (`CoinAmount.positive` → `NonPositiveAmountException`).
2. Load `GuildCoinConfig` via `GuildCoinConfigPort.get(guildId)`.
3. **Authorize**: if `config.moderatorRoleId == null` → `ModeratorRoleNotConfiguredException`
   (fails closed); else require `actorIsAdmin || actorRoleIds.contains(moderatorRoleId)`, else
   `ModeratorNotAuthorizedException`.
4. **Idempotency**: `CoinLedgerPort.findByInteractionId(interactionId)` — if present, return
   `Outcome.DUPLICATE` with the recorded amounts and the member's current balance (no write).
5. `CoinLedgerPort.lockAccount(guildId, targetMemberId)` (advisory xact lock).
6. `currentBalance = CoinLedgerPort.currentBalance(guildId, targetMemberId)`.
7. Build the balanced plan via the pure domain policy:
   - `GRANT` → `CoinLedgerPolicy.planGrant(guildId, targetMemberId, currentBalance, amount, cap)`.
   - `DEDUCTION` → `CoinLedgerPolicy.planDeduction(...)` — throws `OverdrawException` if
     `amount > currentBalance` (transaction rolls back; nothing posted).
8. `CoinLedgerPort.append(newMovement, plan)` — inserts the movement (`ON CONFLICT(interaction_id)
   DO NOTHING`) and its entries. If the insert hit the conflict (a race with a concurrent
   duplicate), treat as `DUPLICATE`.
9. Return `APPLIED` with `newBalance = currentBalance + plan.credited − (DEDUCTION ? amount : 0)`,
   `creditedAmount`, `forfeitedAmount`, `cap`.

*Maps to*: FR-002, FR-003, FR-007–FR-011a, FR-014–FR-019, FR-021; US1 scenarios 1–7.

## `CoinQueryService.viewBalance` — read path

`@Service`, `@Transactional(readOnly = true)`.

**Request** — `ViewBalanceRequest(long guildId, long memberId, int historyLimit)`.

**Result** — `BalanceView`:

| Field | Type | Notes |
|-------|------|-------|
| `balance` | `int` | `CoinLedgerPort.currentBalance(...)` — derived `SUM`. |
| `cap` | `int` | The server's effective cap. |
| `recent` | `List<MovementSummary>` | Newest first, ≤ `historyLimit`. |

`MovementSummary(AdjustmentType type, int requested, int credited, int forfeited, String reason,
long moderatorId, Instant createdAt)`.

Returns `balance = 0` and an empty `recent` for a member with no movements (no error).

*Maps to*: FR-012, FR-013, FR-020, FR-022, FR-023; US2 scenarios 1–4.

## `CoinConfigService.configure` — per-server config

`@Service`, `@Transactional` (read-write).

**Request** — `ConfigureCoinsRequest(long guildId, boolean actorIsAdmin, Long moderatorRoleId,
Integer cap)` — `moderatorRoleId`/`cap` are nullable; a null leaves that setting unchanged.

**Result** — `CoinConfigResult(Long moderatorRoleId, int cap)` — the effective config after upsert.

**Algorithm**: require `actorIsAdmin` (else `ModeratorNotAuthorizedException`); validate `cap ≥ 0`
when present (`NonPositiveAmountException` is reserved for adjustment amounts, so use a config
validation — `cap` may be 0); `GuildCoinConfigPort.upsert(guildId, moderatorRoleId, cap)`.

*Maps to*: FR-007 (configurable cap, default 12 when never set), FR-011a (designate the role).

## Error model (`bot.domain`)

Abstract `DomainException(String messageKey, Object... args)`; handlers resolve `messageKey`
against `messages/coin-messages.properties`. Typed subclasses:

| Exception | Thrown when | Renders as |
|-----------|-------------|------------|
| `NonPositiveAmountException` | adjustment `amount ≤ 0` | "Amount must be ≥ 1." |
| `OverdrawException` | deduction `> balance` | "That would overdraw …; nothing changed." |
| `ModeratorNotAuthorizedException` | caller lacks the configured role / admin | "You're not authorized." |
| `ModeratorRoleNotConfiguredException` | no moderator role configured | "An admin must run /coins-config first." |

Throwing aborts the transaction, guaranteeing the spec's "fails atomically and changes nothing".
