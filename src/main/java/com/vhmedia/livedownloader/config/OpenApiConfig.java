package com.vhmedia.livedownloader.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	public static final String TAG_VIDEO_EDITOR = "Video Editor";
	public static final String TAG_RECORDINGS = "Recordings";
	public static final String TAG_STREAM_PROBE = "Stream Probe";
	public static final String TAG_HEALTH = "Health";

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("VH MEDIA LIVE DOWNLOADER")
						.description("""
								Livestream recording and video editor API.

								**Video Editor (V1):** upload or import a source MP4, split/reorder/trim/speed the *visual* \
								timeline, then export H.264 MP4.

								**Audio Locked:** audio is always `original[0..outputDuration]`. \
								Reorder visual does **not** reorder audio. Speed visual does **not** speed/pitch audio \
								(no `atempo`). Trim / `output-range` **does** trim audio to the new output length \
								(`atrim`+AAC when shorter than the source; `-map 0:a:0?` when output ≈ source). \
								Never audio concat. Visual speed uses `setpts` only. \
								SSE `durationMillis` / 100% is `outputDurationMillis` (source 27s trimmed to 25s → 100% at 25s). \
								Project `durationMillis` is source (compat). Clients cannot pass raw FFmpeg arguments, \
								filters, or filesystem paths. Optional `timelineVersion` (`TIMELINE_CONFLICT` on stale clients). \
								Stack traces and FFmpeg argv are never returned to clients.

								Swagger UI is disabled in the `prod` profile.
								""")
						.version("v1"))
				.addTagsItem(new Tag()
						.name(TAG_VIDEO_EDITOR)
						.description("Visual split/merge/boundary/output-range/trim/speed/reorder. Audio locked: original[0..outputDuration]."))
				.addTagsItem(new Tag()
						.name(TAG_RECORDINGS)
						.description("Probe-backed livestream recording jobs"))
				.addTagsItem(new Tag()
						.name(TAG_STREAM_PROBE)
						.description("ffprobe metadata for a direct stream URL"))
				.addTagsItem(new Tag()
						.name(TAG_HEALTH)
						.description("Liveness"));
	}

	@Bean
	public GroupedOpenApi videoEditorApi() {
		return GroupedOpenApi.builder()
				.group("video-editor")
				.displayName(TAG_VIDEO_EDITOR)
				.pathsToMatch("/api/v1/editor/**")
				.build();
	}

	@Bean
	public GroupedOpenApi recordingsApi() {
		return GroupedOpenApi.builder()
				.group("recordings")
				.displayName(TAG_RECORDINGS)
				.pathsToMatch("/api/v1/recordings/**")
				.build();
	}

	@Bean
	public GroupedOpenApi streamProbeApi() {
		return GroupedOpenApi.builder()
				.group("stream-probe")
				.displayName(TAG_STREAM_PROBE)
				.pathsToMatch("/api/v1/streams/**")
				.build();
	}

	@Bean
	public GroupedOpenApi healthApi() {
		return GroupedOpenApi.builder()
				.group("health")
				.displayName(TAG_HEALTH)
				.pathsToMatch("/api/v1/health/**")
				.build();
	}
}
