package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.enums.VideoEditSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Value
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EditorProjectResponse {

	UUID id;
	ProjectStatus status;
	VideoEditSourceType sourceType;
	UUID sourceRecordingId;
	UUID sourceAssetId;
	String name;
	String title;
	boolean hasVideo;
	boolean hasAudio;
	String videoCodec;
	String audioCodec;
	Integer width;
	Integer height;
	Double fps;
	/**
	 * Source file duration (probed). Kept for backward compatibility; prefer {@link #sourceDurationMillis}.
	 * Do not treat this as output length.
	 */
	@Schema(description = "Probed source MP4 duration (compat). Prefer sourceDurationMillis. Not the editor output length.")
	Long durationMillis;
	/**
	 * Probed source MP4 duration. Same value as {@link #durationMillis}.
	 */
	@Schema(description = "Probed source MP4 duration in milliseconds.")
	Long sourceDurationMillis;
	/**
	 * Editor output duration: {@code sum(segment visual durations)}. Export {@code -t} and audio length.
	 */
	@Schema(description = "Sum of segment visualDurationMillis (after speed). Use this for output length, not durationMillis.")
	Long outputDurationMillis;
	Long outputBytes;
	String outputBaseName;
	EditorSourceResponse source;
	EditorExportResponse export;
	/**
	 * Alias of {@link #outputDurationMillis}.
	 */
	@Schema(description = "Alias of outputDurationMillis.")
	Long visualDurationMillis;
	/**
	 * Incremented on every project write. Send back on timeline mutations to get TIMELINE_CONFLICT on stale clients.
	 */
	@Schema(description = "Optimistic timeline version. Optional on mutation requests; mismatch → TIMELINE_CONFLICT.")
	long timelineVersion;
	List<EditorSegmentResponse> segments;
	List<EditorAssetResponse> assets;
	String errorMessage;
	Instant createdAt;
	Instant renderedAt;
	Instant updatedAt;
}
