-- Explicit source-file ownership for editor delete/cleanup.
-- UPLOAD / RECORDING_COPY: editor may delete its own bytes.
-- RECORDING_HARDLINK: editor may only unlink its directory entry; never the recording original.

ALTER TABLE video_project
    ADD COLUMN IF NOT EXISTS source_storage_mode VARCHAR(32) NOT NULL DEFAULT 'UPLOAD';

UPDATE video_project
SET source_storage_mode = 'RECORDING_HARDLINK'
WHERE source_type = 'RECORDING'
  AND source_storage_mode = 'UPLOAD';

ALTER TABLE video_project DROP CONSTRAINT IF EXISTS ck_video_project_source_storage_mode;
ALTER TABLE video_project
    ADD CONSTRAINT ck_video_project_source_storage_mode
        CHECK (source_storage_mode IN ('UPLOAD', 'RECORDING_COPY', 'RECORDING_HARDLINK'));
