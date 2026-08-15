-- Visual-only playback rate per VIDEO segment. Default 1.0 (unchanged speed).
-- Audio is never re-timed; output duration must not exceed original audio when locked.

ALTER TABLE video_segment
    ADD COLUMN IF NOT EXISTS playback_rate DOUBLE PRECISION NOT NULL DEFAULT 1.0;
