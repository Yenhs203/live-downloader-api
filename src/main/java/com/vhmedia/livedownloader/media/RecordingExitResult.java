package com.vhmedia.livedownloader.media;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.UUID;

@Value
@Builder
public class RecordingExitResult {

	UUID jobId;
	RecordingExitReason reason;
	int exitCode;
	Path outputPath;
	String errorMessage;
	Long durationMillis;
	Long downloadedBytes;
}
