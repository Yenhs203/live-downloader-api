-- Export quality tiers and original-audio flag. Additive; existing rows default to BALANCED / true.

ALTER TABLE video_project
    ADD COLUMN IF NOT EXISTS export_quality VARCHAR(32) NOT NULL DEFAULT 'BALANCED';

ALTER TABLE video_project
    ADD COLUMN IF NOT EXISTS export_keep_original_audio BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE video_export_job
    ADD COLUMN IF NOT EXISTS quality VARCHAR(32) NOT NULL DEFAULT 'BALANCED';

ALTER TABLE video_export_job
    ADD COLUMN IF NOT EXISTS keep_original_audio BOOLEAN NOT NULL DEFAULT TRUE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_video_project_export_quality'
    ) THEN
        ALTER TABLE video_project
            ADD CONSTRAINT ck_video_project_export_quality
                CHECK (export_quality IN ('FAST', 'BALANCED', 'HIGH'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_video_export_job_quality'
    ) THEN
        ALTER TABLE video_export_job
            ADD CONSTRAINT ck_video_export_job_quality
                CHECK (quality IN ('FAST', 'BALANCED', 'HIGH'));
    END IF;
END $$;
