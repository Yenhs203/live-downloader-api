-- Tighten playback_rate to the V1 whitelist. Column itself was added in V9.
-- Do not store derived outputDuration / original trim bounds: those go stale or enable a false reset.

ALTER TABLE video_segment DROP CONSTRAINT IF EXISTS ck_video_segment_playback_rate;
ALTER TABLE video_segment
    ADD CONSTRAINT ck_video_segment_playback_rate
        CHECK (playback_rate IN (0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0));
