CREATE TABLE video_edit_project (
    id                    UUID                        NOT NULL,
    source_type           VARCHAR(32)                 NOT NULL,
    source_recording_id   UUID,
    title                 VARCHAR(255),
    output_base_name      VARCHAR(255)                NOT NULL,
    source_file_path      TEXT                        NOT NULL,
    output_file_path      TEXT,
    status                VARCHAR(32)                 NOT NULL,
    has_video             BOOLEAN                     NOT NULL DEFAULT TRUE,
    has_audio             BOOLEAN                     NOT NULL DEFAULT FALSE,
    video_codec           VARCHAR(64),
    audio_codec           VARCHAR(64),
    width                 INTEGER,
    height                INTEGER,
    fps                   DOUBLE PRECISION,
    duration_millis       BIGINT,
    output_bytes          BIGINT,
    segments_json         TEXT                        NOT NULL DEFAULT '[]',
    error_message         TEXT,
    created_at            TIMESTAMPTZ                 NOT NULL,
    rendered_at           TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ                 NOT NULL,

    CONSTRAINT pk_video_edit_project PRIMARY KEY (id),
    CONSTRAINT uq_video_edit_project_output_base_name UNIQUE (output_base_name),
    CONSTRAINT ck_video_edit_project_source_type CHECK (source_type IN ('UPLOAD', 'RECORDING')),
    CONSTRAINT ck_video_edit_project_status CHECK (status IN (
        'CREATED', 'READY', 'RENDERING', 'COMPLETED', 'FAILED', 'INTERRUPTED', 'DELETED'
    ))
);

CREATE INDEX idx_video_edit_project_status
    ON video_edit_project (status);

CREATE INDEX idx_video_edit_project_created_at
    ON video_edit_project (created_at DESC);

CREATE INDEX idx_video_edit_project_status_created_at
    ON video_edit_project (status, created_at DESC);

CREATE INDEX idx_video_edit_project_source_recording_id
    ON video_edit_project (source_recording_id);
