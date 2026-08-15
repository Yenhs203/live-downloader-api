package com.vhmedia.livedownloader.config;

import com.vhmedia.livedownloader.editor.EditorEncodeProfile;
import com.vhmedia.livedownloader.editor.EditorExportQuality;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.editor")
public class EditorProperties {

	/**
	 * Root for editor files. Blank means {@code {recordingsDirectory}/editor}.
	 */
	private String storageDirectory = "";

	/**
	 * Maximum accepted upload size for source MP4 ({@code POST /api/v1/editor/projects}).
	 */
	@Min(1)
	private long maxUploadBytes = 536_870_912L;

	/**
	 * Maximum IMAGE asset upload size ({@code EDITOR_MAX_IMAGE_UPLOAD_BYTES}).
	 */
	@Min(1)
	private long maxImageUploadBytes = 20_971_520L;

	/**
	 * Max simultaneous FFmpeg editor exports ({@code MAX_CONCURRENT_EDITOR_EXPORTS}).
	 * Independent from {@code MAX_CONCURRENT_RECORDINGS}. Excess starts are rejected with HTTP 429.
	 */
	@Min(1)
	private int maxConcurrentExports = 2;

	@Min(1)
	private int exportTimeoutMinutes = 60;

	@Min(1)
	private int maxSegments = 50;

	/**
	 * Reject trims shorter than this to avoid unstable FFmpeg cuts.
	 */
	@Min(1)
	private int minSegmentMillis = 100;

	/**
	 * Slack for source-range comparisons (trim/split/boundary) and for deciding whether
	 * locked audio can skip {@code atrim} when {@code outputDuration} is within this of the source.
	 * Not a requirement that {@code sum(visual durations) == source duration}.
	 */
	@Min(0)
	private int coverageEpsilonMillis = 50;

	/**
	 * Phase 1B: allow IMAGE visual segments (upload + loop-to-video). Phase 1A keeps this false.
	 */
	private boolean imageSegmentsEnabled = false;

	@Min(1)
	private int maxAssetsPerProject = 30;

	@Min(0)
	private int videoCrf = 23;

	@NotBlank
	private String videoPreset = "veryfast";

	/**
	 * Delete in-progress {@code tmp/} export files after success, failure, or cancel.
	 */
	private boolean deleteTempAfterExport = true;

	/**
	 * Optional extra free-space guard on the editor volume. {@code 0} skips this check.
	 */
	@Min(0)
	private long minFreeDiskBytes = 0L;

	/**
	 * Phase 2 preview generation. Unused in Phase 1.
	 */
	private boolean previewEnabled = false;

	@Min(1)
	private int previewMaxWidth = 1280;

	@Min(0)
	private long previewMaxDurationMillis = 0L;

	/**
	 * x264 encode tiers for {@code quality=FAST|BALANCED|HIGH}. Clients never send {@code -crf}/{@code -preset}.
	 * <ul>
	 *   <li>FAST — {@code veryfast} / CRF 26: quicker drafts, still in the 25–27 watchable band.</li>
	 *   <li>BALANCED — {@code medium} / CRF 23: x264 defaults; normal delivery.</li>
	 *   <li>HIGH — {@code slow} / CRF 20: more motion search, near-transparent for livestream footage.</li>
	 * </ul>
	 */
	@Valid
	private EncodeQualityProperties encodeQuality = new EncodeQualityProperties();

	public EditorEncodeProfile encodeProfile(EditorExportQuality quality) {
		EditorExportQuality tier = quality == null ? EditorExportQuality.BALANCED : quality;
		EncodeQualityProperties profiles = ensureEncodeQuality();
		EncodeQualityTier knobs = switch (tier) {
			case FAST -> profiles.getFast();
			case HIGH -> profiles.getHigh();
			case BALANCED -> profiles.getBalanced();
		};
		if (knobs == null || knobs.getPreset() == null || knobs.getPreset().isBlank()) {
			return switch (tier) {
				case FAST -> new EditorEncodeProfile("veryfast", 26);
				case HIGH -> new EditorEncodeProfile("slow", 20);
				case BALANCED -> new EditorEncodeProfile("medium", 23);
			};
		}
		return new EditorEncodeProfile(knobs.getPreset(), knobs.getCrf());
	}

	/** @deprecated prefer {@link #encodeProfile(EditorExportQuality)} BALANCED */
	public int getVideoCrf() {
		return encodeProfile(EditorExportQuality.BALANCED).crf();
	}

	public void setVideoCrf(int videoCrf) {
		ensureEncodeQuality().getBalanced().setCrf(videoCrf);
		this.videoCrf = videoCrf;
	}

	/** @deprecated prefer {@link #encodeProfile(EditorExportQuality)} BALANCED */
	public String getVideoPreset() {
		return encodeProfile(EditorExportQuality.BALANCED).preset();
	}

	public void setVideoPreset(String videoPreset) {
		ensureEncodeQuality().getBalanced().setPreset(videoPreset);
		this.videoPreset = videoPreset;
	}

	private EncodeQualityProperties ensureEncodeQuality() {
		if (encodeQuality == null) {
			encodeQuality = new EncodeQualityProperties();
		}
		return encodeQuality;
	}

	@Getter
	@Setter
	public static class EncodeQualityProperties {
		@Valid
		private EncodeQualityTier fast = EncodeQualityTier.of("veryfast", 26);
		@Valid
		private EncodeQualityTier balanced = EncodeQualityTier.of("medium", 23);
		@Valid
		private EncodeQualityTier high = EncodeQualityTier.of("slow", 20);
	}

	@Getter
	@Setter
	public static class EncodeQualityTier {
		@NotBlank
		private String preset;
		@Min(0)
		private int crf;

		public static EncodeQualityTier of(String preset, int crf) {
			EncodeQualityTier tier = new EncodeQualityTier();
			tier.setPreset(preset);
			tier.setCrf(crf);
			return tier;
		}
	}

	/** @deprecated prefer {@link #getMaxImageUploadBytes()} */
	public long getMaxAssetBytes() {
		return maxImageUploadBytes;
	}

	public void setMaxAssetBytes(long maxAssetBytes) {
		this.maxImageUploadBytes = maxAssetBytes;
	}

	/** @deprecated prefer {@link #getMaxConcurrentExports()} */
	public int getMaxConcurrentRenders() {
		return maxConcurrentExports;
	}

	public void setMaxConcurrentRenders(int maxConcurrentRenders) {
		this.maxConcurrentExports = maxConcurrentRenders;
	}

	/** @deprecated prefer {@link #getExportTimeoutMinutes()} */
	public int getRenderTimeoutMinutes() {
		return exportTimeoutMinutes;
	}

	public void setRenderTimeoutMinutes(int renderTimeoutMinutes) {
		this.exportTimeoutMinutes = renderTimeoutMinutes;
	}
}
