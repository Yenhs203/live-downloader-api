ALTER TABLE video_edit_project DROP CONSTRAINT ck_video_edit_project_status;

ALTER TABLE video_edit_project ADD CONSTRAINT ck_video_edit_project_status CHECK (status IN (
    'CREATED',
    'READY',
    'RENDERING',
    'STOPPING',
    'COMPLETED',
    'FAILED',
    'INTERRUPTED',
    'CANCELLED',
    'DELETED'
));

ALTER TABLE video_edit_project
    ADD COLUMN export_settings_json TEXT NOT NULL DEFAULT '{"fps":"ORIGINAL","resolution":"ORIGINAL","codec":"H264"}';
