CREATE TABLE video_edit_asset (
    id                 UUID         NOT NULL,
    project_id         UUID         NOT NULL,
    type               VARCHAR(16)  NOT NULL,
    original_filename  VARCHAR(255),
    content_type       VARCHAR(128) NOT NULL,
    file_path          TEXT         NOT NULL,
    byte_size          BIGINT       NOT NULL,
    width              INTEGER,
    height             INTEGER,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_video_edit_asset PRIMARY KEY (id),
    CONSTRAINT fk_video_edit_asset_project
        FOREIGN KEY (project_id) REFERENCES video_edit_project (id) ON DELETE CASCADE,
    CONSTRAINT ck_video_edit_asset_type CHECK (type IN ('IMAGE'))
);

CREATE INDEX idx_video_edit_asset_project_id
    ON video_edit_asset (project_id);

CREATE INDEX idx_video_edit_asset_project_created_at
    ON video_edit_asset (project_id, created_at ASC);
