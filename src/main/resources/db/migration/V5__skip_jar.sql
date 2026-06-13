-- Feature 006: skip jar. ADDITIVE only — V1/V2/V3/V4 are immutable and untouched.
-- Two new tables (per-server skip config + per-run contribution ledger) plus two ADDITIVE rewrites
-- of the coin ledger CHECKs to admit the new SKIP_POT account and SKIP_JAR movement type. The
-- contribution coin spend REUSES the V2 ledger (coin_movement + coin_ledger_entry) — no second
-- economy. The current run, dwell baseline, and earner set are READ from existing state
-- (queue_rotation_state, coin_movement); no existing table gains a column. Leans on Postgres
-- (CHECK, composite PK, ON CONFLICT, REFERENCES) per Principles I & IV.

-- ---------------------------------------------------------------------------
-- Per-server skip-jar configuration (MUTABLE config, not ledger data).
-- Absent row => domain defaults (3, 24h, true); created on first admin change.
-- ---------------------------------------------------------------------------
CREATE TABLE guild_skip_jar_config (
    guild_id           bigint      PRIMARY KEY,
    threshold_floor    int         NOT NULL DEFAULT 3     CHECK (threshold_floor > 0), -- FR-008/FR-015
    dwell_seconds      bigint      NOT NULL DEFAULT 86400 CHECK (dwell_seconds > 0),   -- 24 h (FR-007/FR-016)
    participation_gate boolean     NOT NULL DEFAULT true,                              -- FR-004/FR-005
    updated_at         timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Skip contribution: one member's single vote for one run. Counts the jar and
-- enforces once-per-run (FR-002). MUTABLE per-run social state — NOT ledger data
-- (the SKIP_JAR coin_movement is the immutable economic record). Superseded for
-- free when the run rolls over (current_week_number increments).
-- ---------------------------------------------------------------------------
CREATE TABLE skip_contribution (
    guild_id    bigint      NOT NULL,
    week_number int         NOT NULL,                              -- run key = rotation current_week_number (D-3)
    member_id   bigint      NOT NULL,
    movement_id bigint      NOT NULL REFERENCES coin_movement (id), -- the balanced SKIP_JAR spend behind this vote
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (guild_id, week_number, member_id)                 -- once per member per run; (guild,week) prefix serves count
);

-- ---------------------------------------------------------------------------
-- Additive ledger extensions (the V2/V3/V4 files stay immutable). Re-add the two
-- CHECKs to admit the new SKIP_POT account and SKIP_JAR movement type. SKIP_POT
-- carries no member_id, so the V2 table-level (account='MEMBER')=(member_id IS NOT
-- NULL) CHECK is satisfied unchanged and is NOT touched here.
-- ---------------------------------------------------------------------------
ALTER TABLE coin_ledger_entry DROP CONSTRAINT coin_ledger_entry_account_check;
ALTER TABLE coin_ledger_entry ADD  CONSTRAINT coin_ledger_entry_account_check
    CHECK (account IN ('MEMBER', 'TREASURY', 'FORFEIT', 'POT', 'SKIP_POT'));

ALTER TABLE coin_movement DROP CONSTRAINT coin_movement_type_check;
ALTER TABLE coin_movement ADD  CONSTRAINT coin_movement_type_check
    CHECK (type IN ('GRANT', 'DEDUCTION', 'QUEUE_PROPOSE', 'QUEUE_BUMP', 'QUEUE_REFUND',
                    'PARTICIPATION', 'SKIP_JAR'));
