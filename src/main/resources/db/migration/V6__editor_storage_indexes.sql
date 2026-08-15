-- Additive editor storage/index hardening. Does not recreate V5 tables.
-- Explicit FK delete behavior (projects are soft-deleted; children stay until purged).

ALTER TABLE video_asset
    ADD COLUMN IF NOT EXISTS storage_file_name VARCHAR(255);

UPDATE video_asset
SET storage_file_name = COALESCE(
        NULLIF(regexp_replace(storage_path, '.*[\\/]', ''), ''),
        'asset.bin'
    )
WHERE storage_file_name IS NULL;

ALTER TABLE video_asset
    ALTER COLUMN storage_file_name SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_video_asset_project_storage_file
    ON video_asset (project_id, storage_file_name);

CREATE INDEX IF NOT EXISTS idx_video_export_job_project_id
    ON video_export_job (project_id);

CREATE INDEX IF NOT EXISTS idx_video_export_job_created_at
    ON video_export_job (created_at DESC);

ALTER TABLE video_asset DROP CONSTRAINT IF EXISTS fk_video_asset_project;
ALTER TABLE video_asset
    ADD CONSTRAINT fk_video_asset_project
        FOREIGN KEY (project_id) REFERENCES video_project (id) ON DELETE RESTRICT;

ALTER TABLE video_segment DROP CONSTRAINT IF EXISTS fk_video_segment_project;
ALTER TABLE video_segment
    ADD CONSTRAINT fk_video_segment_project
        FOREIGN KEY (project_id) REFERENCES video_project (id) ON DELETE RESTRICT;

ALTER TABLE video_segment DROP CONSTRAINT IF EXISTS fk_video_segment_asset;
ALTER TABLE video_segment
    ADD CONSTRAINT fk_video_segment_asset
        FOREIGN KEY (asset_id) REFERENCES video_asset (id) ON DELETE RESTRICT;

ALTER TABLE video_export_job DROP CONSTRAINT IF EXISTS fk_video_export_job_project;
ALTER TABLE video_export_job
    ADD CONSTRAINT fk_video_export_job_project
        FOREIGN KEY (project_id) REFERENCES video_project (id) ON DELETE RESTRICT;

ALTER TABLE video_project DROP CONSTRAINT IF EXISTS fk_video_project_source_asset;
ALTER TABLE video_project
    ADD CONSTRAINT fk_video_project_source_asset
        FOREIGN KEY (source_asset_id) REFERENCES video_asset (id) ON DELETE RESTRICT;
