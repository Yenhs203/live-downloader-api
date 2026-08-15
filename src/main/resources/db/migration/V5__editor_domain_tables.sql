-- Editor bounded context: project / asset / segment / export job.
-- Independent from live_download_job. Project status is not a recording lifecycle.

CREATE TABLE video_project (
    id                    UUID         NOT NULL,
    name                  VARCHAR(255),
    status                VARCHAR(32)  NOT NULL,
    source_type           VARCHAR(32)  NOT NULL,
    source_recording_id   UUID,
    source_asset_id       UUID,
    output_base_name      VARCHAR(255) NOT NULL,
    has_video             BOOLEAN      NOT NULL DEFAULT TRUE,
    has_audio             BOOLEAN      NOT NULL DEFAULT FALSE,
    video_codec           VARCHAR(64),
    audio_codec           VARCHAR(64),
    width                 INTEGER,
    height                INTEGER,
    fps                   DOUBLE PRECISION,
    duration_millis       BIGINT,
    export_fps            VARCHAR(32)  NOT NULL DEFAULT 'ORIGINAL',
    export_resolution     VARCHAR(32)  NOT NULL DEFAULT 'ORIGINAL',
    export_codec          VARCHAR(32)  NOT NULL DEFAULT 'H264',
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_video_project PRIMARY KEY (id),
    CONSTRAINT uq_video_project_output_base_name UNIQUE (output_base_name),
    CONSTRAINT ck_video_project_status CHECK (status IN ('CREATED', 'READY', 'DELETED')),
    CONSTRAINT ck_video_project_source_type CHECK (source_type IN ('UPLOAD', 'RECORDING'))
);

CREATE TABLE video_asset (
    id                 UUID         NOT NULL,
    project_id         UUID         NOT NULL,
    type               VARCHAR(16)  NOT NULL,
    original_filename  VARCHAR(255),
    storage_path       TEXT         NOT NULL,
    mime_type          VARCHAR(128) NOT NULL,
    duration_millis    BIGINT,
    width              INTEGER,
    height             INTEGER,
    video_codec        VARCHAR(64),
    audio_codec        VARCHAR(64),
    byte_size          BIGINT       NOT NULL DEFAULT 0,
    primary_source     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_video_asset PRIMARY KEY (id),
    CONSTRAINT fk_video_asset_project FOREIGN KEY (project_id) REFERENCES video_project (id),
    CONSTRAINT ck_video_asset_type CHECK (type IN ('VIDEO', 'IMAGE'))
);

CREATE TABLE video_segment (
    id                   UUID         NOT NULL,
    project_id           UUID         NOT NULL,
    asset_id             UUID         NOT NULL,
    type                 VARCHAR(16)  NOT NULL,
    label                VARCHAR(32),
    source_start_millis  BIGINT,
    source_end_millis    BIGINT,
    duration_millis      BIGINT       NOT NULL,
    position             INTEGER      NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_video_segment PRIMARY KEY (id),
    CONSTRAINT fk_video_segment_project FOREIGN KEY (project_id) REFERENCES video_project (id),
    CONSTRAINT fk_video_segment_asset FOREIGN KEY (asset_id) REFERENCES video_asset (id),
    CONSTRAINT ck_video_segment_type CHECK (type IN ('VIDEO', 'IMAGE')),
    CONSTRAINT uq_video_segment_project_position UNIQUE (project_id, position)
);

CREATE TABLE video_export_job (
    id                 UUID         NOT NULL,
    project_id         UUID         NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    fps_preset         VARCHAR(32)  NOT NULL DEFAULT 'ORIGINAL',
    requested_fps      INTEGER,
    resolution         VARCHAR(32)  NOT NULL DEFAULT 'ORIGINAL',
    video_codec        VARCHAR(32)  NOT NULL DEFAULT 'H264',
    progress_millis    BIGINT,
    progress_percent   DOUBLE PRECISION,
    output_file_path   TEXT,
    output_bytes       BIGINT,
    error_message      TEXT,
    cancel_requested   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_video_export_job PRIMARY KEY (id),
    CONSTRAINT fk_video_export_job_project FOREIGN KEY (project_id) REFERENCES video_project (id),
    CONSTRAINT ck_video_export_job_status CHECK (status IN (
        'CREATED', 'PREPARING', 'RENDERING', 'FINALIZING', 'COMPLETED', 'FAILED', 'CANCELLED'
    ))
);

CREATE INDEX idx_video_project_status ON video_project (status);
CREATE INDEX idx_video_project_created_at ON video_project (created_at DESC);
CREATE INDEX idx_video_asset_project_id ON video_asset (project_id);
CREATE INDEX idx_video_segment_project_position ON video_segment (project_id, position);
CREATE INDEX idx_video_export_job_project_created ON video_export_job (project_id, created_at DESC);
CREATE INDEX idx_video_export_job_status ON video_export_job (status);

-- Migrate existing editor rows if the previous tables are present.
DO $$
BEGIN
    IF to_regclass('public.video_edit_project') IS NULL THEN
        RETURN;
    END IF;

    INSERT INTO video_project (
        id, name, status, source_type, source_recording_id, output_base_name,
        has_video, has_audio, video_codec, audio_codec, width, height, fps, duration_millis,
        export_fps, export_resolution, export_codec, created_at, updated_at
    )
    SELECT
        p.id,
        p.title,
        CASE WHEN p.status = 'DELETED' THEN 'DELETED'
             WHEN p.status = 'CREATED' THEN 'CREATED'
             ELSE 'READY' END,
        p.source_type,
        p.source_recording_id,
        p.output_base_name,
        p.has_video,
        p.has_audio,
        p.video_codec,
        p.audio_codec,
        p.width,
        p.height,
        p.fps,
        p.duration_millis,
        COALESCE((p.export_settings_json::json ->> 'fps'), 'ORIGINAL'),
        COALESCE((p.export_settings_json::json ->> 'resolution'), 'ORIGINAL'),
        COALESCE((p.export_settings_json::json ->> 'codec'), 'H264'),
        p.created_at,
        p.updated_at
    FROM video_edit_project p;

    INSERT INTO video_asset (
        id, project_id, type, original_filename, storage_path, mime_type,
        duration_millis, width, height, video_codec, audio_codec, byte_size, primary_source, created_at
    )
    SELECT
        gen_random_uuid(),
        p.id,
        'VIDEO',
        'source.mp4',
        p.source_file_path,
        'video/mp4',
        p.duration_millis,
        p.width,
        p.height,
        p.video_codec,
        p.audio_codec,
        0,
        TRUE,
        p.created_at
    FROM video_edit_project p;

    UPDATE video_project vp
    SET source_asset_id = a.id
    FROM video_asset a
    WHERE a.project_id = vp.id AND a.primary_source = TRUE;

    IF to_regclass('public.video_edit_asset') IS NOT NULL THEN
        INSERT INTO video_asset (
            id, project_id, type, original_filename, storage_path, mime_type,
            duration_millis, width, height, video_codec, audio_codec, byte_size, primary_source, created_at
        )
        SELECT
            ea.id,
            ea.project_id,
            'IMAGE',
            ea.original_filename,
            ea.file_path,
            ea.content_type,
            NULL,
            ea.width,
            ea.height,
            NULL,
            NULL,
            ea.byte_size,
            FALSE,
            ea.created_at
        FROM video_edit_asset ea
        WHERE EXISTS (SELECT 1 FROM video_project vp WHERE vp.id = ea.project_id);
    END IF;

    INSERT INTO video_segment (
        id, project_id, asset_id, type, label,
        source_start_millis, source_end_millis, duration_millis, position, created_at, updated_at
    )
    SELECT
        gen_random_uuid(),
        vp.id,
        vp.source_asset_id,
        'VIDEO',
        'A',
        0,
        vp.duration_millis,
        COALESCE(vp.duration_millis, 0),
        0,
        vp.created_at,
        vp.updated_at
    FROM video_project vp
    WHERE vp.source_asset_id IS NOT NULL AND vp.duration_millis IS NOT NULL AND vp.duration_millis > 0;

    INSERT INTO video_export_job (
        id, project_id, status, fps_preset, requested_fps, resolution, video_codec,
        output_file_path, output_bytes, error_message, cancel_requested,
        created_at, started_at, completed_at, updated_at
    )
    SELECT
        gen_random_uuid(),
        p.id,
        CASE p.status
            WHEN 'COMPLETED' THEN 'COMPLETED'
            WHEN 'FAILED' THEN 'FAILED'
            WHEN 'CANCELLED' THEN 'CANCELLED'
            WHEN 'INTERRUPTED' THEN 'FAILED'
            WHEN 'RENDERING' THEN 'FAILED'
            WHEN 'STOPPING' THEN 'FAILED'
            ELSE 'FAILED'
        END,
        COALESCE((p.export_settings_json::json ->> 'fps'), 'ORIGINAL'),
        CASE
            WHEN (p.export_settings_json::json ->> 'fps') IN ('24', '25', '30', '50')
                THEN (p.export_settings_json::json ->> 'fps')::INTEGER
            ELSE NULL
        END,
        COALESCE((p.export_settings_json::json ->> 'resolution'), 'ORIGINAL'),
        COALESCE((p.export_settings_json::json ->> 'codec'), 'H264'),
        p.output_file_path,
        p.output_bytes,
        COALESCE(p.error_message, CASE WHEN p.status IN ('RENDERING', 'STOPPING', 'INTERRUPTED')
            THEN 'Interrupted by schema migration / application restart'
            ELSE NULL END),
        FALSE,
        p.created_at,
        p.rendered_at,
        p.rendered_at,
        p.updated_at
    FROM video_edit_project p
    WHERE p.status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'RENDERING', 'STOPPING');

    ALTER TABLE video_project
        ADD CONSTRAINT fk_video_project_source_asset
            FOREIGN KEY (source_asset_id) REFERENCES video_asset (id);

    DROP TABLE IF EXISTS video_edit_asset;
    DROP TABLE IF EXISTS video_edit_project;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_video_project_source_asset'
    ) THEN
        ALTER TABLE video_project
            ADD CONSTRAINT fk_video_project_source_asset
                FOREIGN KEY (source_asset_id) REFERENCES video_asset (id);
    END IF;
END $$;
