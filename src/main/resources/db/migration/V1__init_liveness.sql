-- Foundation slice: a tiny seeded table the liveness probe reads to prove the data store is
-- reachable. Append-only history — once applied, this migration is never edited (a change ships as
-- V2). Idempotent seed leans on Postgres ON CONFLICT, which is the only supported engine.
CREATE TABLE health_check (
    id         smallint    PRIMARY KEY,
    label      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO health_check (id, label)
VALUES (1, 'ok')
ON CONFLICT (id) DO NOTHING;
