-- Coin economy: per-server append-only, double-entry ledger + per-server configuration.
-- Immutable once applied (a change ships as V3). Leans on Postgres (bigserial, ON CONFLICT,
-- plpgsql triggers, deferred constraint triggers) per Constitution Principles I & III.

-- ---------------------------------------------------------------------------
-- Per-server configuration (MUTABLE — this is config, not ledger data).
-- ---------------------------------------------------------------------------
CREATE TABLE guild_coin_config (
    guild_id          bigint      PRIMARY KEY,
    moderator_role_id bigint,                                  -- NULL => no one authorized (fail closed)
    coin_cap          int         NOT NULL DEFAULT 12 CHECK (coin_cap >= 0),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Movement: one economic event (a grant or a deduction). APPEND-ONLY.
-- ---------------------------------------------------------------------------
CREATE TABLE coin_movement (
    id               bigserial   PRIMARY KEY,
    guild_id         bigint      NOT NULL,
    member_id        bigint      NOT NULL,
    moderator_id     bigint      NOT NULL,
    type             text        NOT NULL CHECK (type IN ('GRANT', 'DEDUCTION')),
    requested_amount int         NOT NULL CHECK (requested_amount > 0),
    credited_amount  int         NOT NULL DEFAULT 0 CHECK (credited_amount >= 0),
    forfeited_amount int         NOT NULL DEFAULT 0 CHECK (forfeited_amount >= 0),
    reason           text,
    interaction_id   bigint      NOT NULL UNIQUE,              -- at-most-once idempotency key
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX coin_movement_member_recent_idx
    ON coin_movement (guild_id, member_id, id DESC);

-- ---------------------------------------------------------------------------
-- Ledger entry: the balanced postings of a movement (sum to zero). APPEND-ONLY.
-- ---------------------------------------------------------------------------
CREATE TABLE coin_ledger_entry (
    id          bigserial   PRIMARY KEY,
    movement_id bigint      NOT NULL REFERENCES coin_movement (id),
    guild_id    bigint      NOT NULL,
    account     text        NOT NULL CHECK (account IN ('MEMBER', 'TREASURY', 'FORFEIT')),
    member_id   bigint,
    amount      bigint      NOT NULL,                          -- signed: + credits, - debits
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK ((account = 'MEMBER') = (member_id IS NOT NULL))     -- member_id set iff MEMBER account
);

CREATE INDEX coin_ledger_entry_balance_idx
    ON coin_ledger_entry (guild_id, member_id);

-- ---------------------------------------------------------------------------
-- I1 — Append-only: reject UPDATE/DELETE on both ledger tables.
-- ---------------------------------------------------------------------------
CREATE FUNCTION coin_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'coin ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER coin_movement_no_mutation
    BEFORE UPDATE OR DELETE ON coin_movement
    FOR EACH ROW EXECUTE FUNCTION coin_forbid_mutation();

CREATE TRIGGER coin_entry_no_mutation
    BEFORE UPDATE OR DELETE ON coin_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION coin_forbid_mutation();

-- ---------------------------------------------------------------------------
-- I2 — Balanced movements: each movement's entries sum to zero (checked at commit).
-- ---------------------------------------------------------------------------
CREATE FUNCTION coin_assert_balanced() RETURNS trigger AS $$
BEGIN
    IF (SELECT COALESCE(SUM(amount), 0) FROM coin_ledger_entry WHERE movement_id = NEW.movement_id) <> 0 THEN
        RAISE EXCEPTION 'coin movement % is not balanced (entries must sum to zero)', NEW.movement_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER coin_entry_balanced
    AFTER INSERT ON coin_ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION coin_assert_balanced();

-- ---------------------------------------------------------------------------
-- I3 — Non-negative balances: an affected MEMBER account never sums below zero.
-- (The cap is NOT enforced here: a lowered cap may sit above an existing balance.)
-- ---------------------------------------------------------------------------
CREATE FUNCTION coin_assert_non_negative() RETURNS trigger AS $$
BEGIN
    IF NEW.account = 'MEMBER'
       AND (SELECT COALESCE(SUM(amount), 0)
              FROM coin_ledger_entry
             WHERE guild_id = NEW.guild_id AND account = 'MEMBER' AND member_id = NEW.member_id) < 0 THEN
        RAISE EXCEPTION 'member % balance would be negative in guild %', NEW.member_id, NEW.guild_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER coin_entry_non_negative
    AFTER INSERT ON coin_ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION coin_assert_non_negative();
