-- Optimistic concurrency for timeline mutations.
-- Pessimistic FOR UPDATE already serializes split/resize in one transaction;
-- timeline_version lets a stale client fail with TIMELINE_CONFLICT instead of overwriting.

ALTER TABLE video_project
    ADD COLUMN IF NOT EXISTS timeline_version BIGINT NOT NULL DEFAULT 0;
