-- Feature 005: participation earning. ADDITIVE only — V1/V2/V3 are immutable and untouched.
-- New per-server participation config, the designated-voice-channel set, per-member accrual state,
-- and a sequence for negative-namespaced synthetic ledger ids; plus one ADDITIVE rewrite of the
-- coin_movement type CHECK to admit 'PARTICIPATION'. Participation credits REUSE the V2 ledger
-- (coin_movement + coin_ledger_entry) — no second economy. Leans on Postgres (CHECK, composite PKs,
-- ON CONFLICT upsert, a sequence) per Principles I & IV.

-- ---------------------------------------------------------------------------
-- Per-server participation configuration (MUTABLE config, not ledger data).
-- ---------------------------------------------------------------------------
CREATE TABLE guild_participation_config (
    guild_id            bigint      PRIMARY KEY,
    minutes_per_drop    int         NOT NULL DEFAULT 60 CHECK (minutes_per_drop > 0), -- FR-002
    coins_per_drop      int         NOT NULL DEFAULT 1  CHECK (coins_per_drop > 0),   -- FR-002
    free_first_proposal boolean     NOT NULL DEFAULT false,                           -- FR-017/018
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Designated voice channels: the per-server set participation is registered on
-- (FR-012/013/015). One-to-many; add is idempotent, reset deletes all rows.
-- ---------------------------------------------------------------------------
CREATE TABLE participation_voice_channel (
    guild_id   bigint      NOT NULL,
    channel_id bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (guild_id, channel_id)                          -- (guild_id) prefix serves list/contains
);

-- ---------------------------------------------------------------------------
-- Per-member accrual: banked sub-drop seconds + last sample instant (MUTABLE).
-- ---------------------------------------------------------------------------
CREATE TABLE participation_accrual (
    guild_id        bigint      NOT NULL,
    member_id       bigint      NOT NULL,
    banked_seconds  bigint      NOT NULL DEFAULT 0 CHECK (banked_seconds >= 0), -- unminted remainder
    last_sampled_at timestamptz,                                                -- null until first observed
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (guild_id, member_id)                           -- upserted via ON CONFLICT DO UPDATE
);

-- ---------------------------------------------------------------------------
-- Synthetic ledger ids for participation drops. interaction_id = -nextval(...)
-- so negative values never collide with positive Discord snowflakes (idempotency
-- UNIQUE column only; the real at-most-once guard is the banked-seconds decrement).
-- ---------------------------------------------------------------------------
CREATE SEQUENCE participation_drop_seq;

-- ---------------------------------------------------------------------------
-- Additive ledger extension (the V2/V3 files stay immutable). Re-add the
-- coin_movement type CHECK to include the new 'PARTICIPATION' value. The
-- coin_ledger_entry_account_check already allows MEMBER/TREASURY/FORFEIT, so
-- participation needs no entry-account change.
-- ---------------------------------------------------------------------------
ALTER TABLE coin_movement DROP CONSTRAINT coin_movement_type_check;
ALTER TABLE coin_movement ADD  CONSTRAINT coin_movement_type_check
    CHECK (type IN ('GRANT', 'DEDUCTION', 'QUEUE_PROPOSE', 'QUEUE_BUMP', 'QUEUE_REFUND',
                    'PARTICIPATION'));
