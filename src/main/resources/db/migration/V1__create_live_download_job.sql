CREATE TABLE live_download_job (
    id                 UUID                        NOT NULL,
    original_url       TEXT                        NOT NULL,
    output_base_name   VARCHAR(255)                NOT NULL,
    temp_file_path     TEXT,
    final_file_path    TEXT,
    status             VARCHAR(32)                 NOT NULL,
    video_codec        VARCHAR(64),
    audio_codec        VARCHAR(64),
    width              INTEGER,
    height             INTEGER,
    fps                DOUBLE PRECISION,
    downloaded_bytes   BIGINT,
    duration_millis    BIGINT,
    error_message      TEXT,
    created_at         TIMESTAMPTZ                 NOT NULL,
    started_at         TIMESTAMPTZ,
    stopped_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ                 NOT NULL,

    CONSTRAINT pk_live_download_job PRIMARY KEY (id),
    CONSTRAINT uq_live_download_job_output_base_name UNIQUE (output_base_name)
);

CREATE INDEX idx_live_download_job_status
    ON live_download_job (status);

CREATE INDEX idx_live_download_job_created_at
    ON live_download_job (created_at DESC);

CREATE INDEX idx_live_download_job_status_created_at
    ON live_download_job (status, created_at DESC);
