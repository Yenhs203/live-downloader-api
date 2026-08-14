package com.vhmedia.livedownloader.media;

import java.util.UUID;

/**
 * Published when FFmpeg emits a progress block on {@code pipe:1}.
 */
public record RecordingProgressEvent(UUID jobId, RecordingProgress progress) {
}
