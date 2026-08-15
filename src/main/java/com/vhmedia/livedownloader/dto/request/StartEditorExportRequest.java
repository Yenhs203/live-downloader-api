package com.vhmedia.livedownloader.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Starts an export job. Clients cannot pass raw FFmpeg arguments.
 * V1 {@code keepOriginalAudio} must be true/omitted. Audio is locked {@code original[0..outputDuration]}
 * (reorder/speed do not move audio; trim shortens it).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartEditorExportRequest {

	@Schema(description = "ORIGINAL, 24, 25, 30, 50, or 60. Visual only.", example = "ORIGINAL")
	@Size(max = 32)
	private String fps;

	@Schema(description = "ORIGINAL, 1080p, 720p, or 540p. Never upscales.", example = "ORIGINAL")
	@Size(max = 32)
	private String resolution;

	@Size(max = 32)
	@JsonAlias("codec")
	@Schema(description = "V1: H264 only.", example = "H264")
	private String videoCodec;

	@Schema(description = "FAST, BALANCED, or HIGH. Mapped to x264 preset/CRF in config.", example = "BALANCED")
	@Size(max = 32)
	private String quality;

	/**
	 * V1 requires {@code true}. Omitted defaults to true. {@code false} is rejected.
	 */
	@Schema(description = "V1 must be true or omitted. Audio Locked: original[0..outputDuration] — not reordered/sped; trim shortens it.", example = "true")
	private Boolean keepOriginalAudio;

	public UpdateEditorExportRequest toUpdateRequest() {
		return UpdateEditorExportRequest.builder()
				.fps(fps)
				.resolution(resolution)
				.codec(videoCodec)
				.videoCodec(videoCodec)
				.quality(quality)
				.keepOriginalAudio(keepOriginalAudio)
				.build();
	}
}
