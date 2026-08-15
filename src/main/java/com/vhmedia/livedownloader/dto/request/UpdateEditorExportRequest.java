package com.vhmedia.livedownloader.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEditorExportRequest {

	@Size(max = 32)
	private String fps;

	@Size(max = 32)
	private String resolution;

	@Size(max = 32)
	private String codec;

	@Size(max = 32)
	private String videoCodec;

	@Size(max = 32)
	private String quality;

	@Schema(description = "V1 must be true or omitted. Audio Locked: original[0..outputDuration].")
	private Boolean keepOriginalAudio;

	public String resolvedCodec() {
		if (videoCodec != null && !videoCodec.isBlank()) {
			return videoCodec;
		}
		return codec;
	}
}
