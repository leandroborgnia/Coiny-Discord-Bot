-- Feature 004: game queue & weekly rotation. ADDITIVE only — V1/V2 are immutable and untouched.
-- New tables for the per-server queue, rotation clock, "wait N games" cooldown, per-slot upvotes,
-- queue config, and a cover-art cache; plus additive extensions to the V2 ledger CHECK constraints
-- (the POT account + the queue movement types). Coin movement REUSES the V2 ledger — no second
-- economy. Leans on Postgres (partial unique indexes, uuid, jsonb, ON CONFLICT) per Principles I & IV.

-- ---------------------------------------------------------------------------
-- Per-server queue configuration (MUTABLE config, not ledger data).
-- ---------------------------------------------------------------------------
CREATE TABLE guild_queue_config (
    guild_id                       bigint      PRIMARY KEY,
    propose_cost                   int         NOT NULL DEFAULT 1 CHECK (propose_cost > 0),
    bump_cost                      int         NOT NULL DEFAULT 1 CHECK (bump_cost > 0),
    announcement_channel_id        bigint,                         -- NULL => silent rotation (FR-037)
    latest_announcement_channel_id bigint,
    latest_announcement_message_id bigint,                         -- the single live count surface (FR-038)
    updated_at                     timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Queue entry: one proposed-game slot — queued or already played.
-- ---------------------------------------------------------------------------
CREATE TABLE queue_entry (
    id                     bigserial   PRIMARY KEY,
    guild_id               bigint      NOT NULL,
    proposer_member_id     bigint      NOT NULL,                   -- retained even if the proposer leaves
    status                 text        NOT NULL CHECK (status IN ('QUEUED', 'PLAYED')),
    position               int,                                    -- set iff QUEUED (tail = max+1)
    game_identity          text        NOT NULL,                   -- which-game key: art cache + cooldown
    game_instance_id       uuid        NOT NULL DEFAULT gen_random_uuid(), -- this appearance; regen on replace
    game_name              text        NOT NULL,
    application_id         bigint,                                 -- when Rich Presence exposed it
    rp_details             text,
    rp_state               text,
    rp_large_image         text,                                  -- captured asset url (art chain step 1)
    rp_small_image         text,
    snapshot               jsonb,                                  -- full Rich-Presence snapshot
    coins_spent            int         NOT NULL DEFAULT 0 CHECK (coins_spent >= 0), -- propose + Σ bumps
    propose_interaction_id bigint      NOT NULL UNIQUE,            -- at-most-once idempotency (FR-015)
    played_week            int,                                    -- set when PLAYED (FR-022)
    created_at             timestamptz NOT NULL DEFAULT now()
);

-- At most one queued slot per member (FR-003).
CREATE UNIQUE INDEX queue_entry_one_queued_per_member_idx
    ON queue_entry (guild_id, proposer_member_id) WHERE status = 'QUEUED';
-- Single unambiguous top / total order (FR-010/021).
CREATE UNIQUE INDEX queue_entry_unique_position_idx
    ON queue_entry (guild_id, position) WHERE status = 'QUEUED';
-- Queue reads and top().
CREATE INDEX queue_entry_read_idx
    ON queue_entry (guild_id, status, position);

-- ---------------------------------------------------------------------------
-- Upvotes: mutable social state (NOT the coin ledger — deletable). Bound to the
-- slot's CURRENT appearance via game_instance_id (FR-030/031).
-- ---------------------------------------------------------------------------
CREATE TABLE queue_upvote (
    slot_id          bigint      NOT NULL REFERENCES queue_entry (id) ON DELETE CASCADE,
    member_id        bigint      NOT NULL,
    game_instance_id uuid        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (slot_id, member_id, game_instance_id)            -- one-or-zero per member per appearance
);

-- ---------------------------------------------------------------------------
-- Rotation clock (per guild — MUTABLE).
-- ---------------------------------------------------------------------------
CREATE TABLE queue_rotation_state (
    guild_id            bigint      PRIMARY KEY,
    current_slot_id     bigint      REFERENCES queue_entry (id),   -- the week's designated slot, or none
    current_week_number int         NOT NULL DEFAULT 0,
    last_pop_at         timestamptz,                               -- rolling-7-day clock; null until bootstrap
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Weekly designation log (append-only audit, FR-022).
-- ---------------------------------------------------------------------------
CREATE TABLE weekly_designation (
    id            bigserial   PRIMARY KEY,
    guild_id      bigint      NOT NULL,
    week_number   int         NOT NULL,
    slot_id       bigint      REFERENCES queue_entry (id),         -- NULL => empty week (FR-009)
    game_identity text,
    game_name     text,
    designated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (guild_id, week_number)                                 -- idempotent advance (FR-016/SC-004)
);

-- ---------------------------------------------------------------------------
-- "Wait N games" cooldown (per member per guild — MUTABLE).
-- ---------------------------------------------------------------------------
CREATE TABLE queue_cooldown (
    guild_id        bigint      NOT NULL,
    member_id       bigint      NOT NULL,
    games_remaining int         NOT NULL CHECK (games_remaining >= 0), -- N at pop; counts down on real pops
    set_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (guild_id, member_id)
);

-- ---------------------------------------------------------------------------
-- Cover-art cache (keyed by game identity — MUTABLE). source NONE = cached miss.
-- ---------------------------------------------------------------------------
CREATE TABLE game_art_cache (
    game_identity text        PRIMARY KEY,
    image_url     text,                                            -- NULL when miss
    source        text        NOT NULL CHECK (source IN ('RICH_PRESENCE', 'IGDB', 'NONE')),
    resolved_at   timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Additive ledger extensions (the V2 file stays immutable). The constraint names
-- below are the verified V2 auto-names for the inline single-column CHECKs
-- (<table>_<column>_check). The table-level coin_ledger_entry_check
-- (member_id IS NOT NULL iff MEMBER) is a DIFFERENT constraint and is NOT touched.
-- ---------------------------------------------------------------------------
ALTER TABLE coin_ledger_entry DROP CONSTRAINT coin_ledger_entry_account_check;
ALTER TABLE coin_ledger_entry ADD  CONSTRAINT coin_ledger_entry_account_check
    CHECK (account IN ('MEMBER', 'TREASURY', 'FORFEIT', 'POT'));

ALTER TABLE coin_movement DROP CONSTRAINT coin_movement_type_check;
ALTER TABLE coin_movement ADD  CONSTRAINT coin_movement_type_check
    CHECK (type IN ('GRANT', 'DEDUCTION', 'QUEUE_PROPOSE', 'QUEUE_BUMP', 'QUEUE_REFUND'));
