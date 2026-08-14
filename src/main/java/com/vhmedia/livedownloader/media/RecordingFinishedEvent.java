package com.vhmedia.livedownloader.media;

/**
 * Published when an FFmpeg recording process terminates.
 * Listeners should update job state and trigger remux when
 * {@link RecordingExitResult#getReason()} is {@link RecordingExitReason#COMPLETED_NATURALLY}
 * or after a successful user stop that still produced a usable TS file.
 */
public record RecordingFinishedEvent(RecordingExitResult result) {
}
