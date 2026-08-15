package com.vhmedia.livedownloader.editor;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * V1 editor output is always MP4. Visual segments may be split, reordered, trimmed, or sped;
 * audio is never reordered or tempo-shifted ({@code atempo}/{@code asetrate} forbidden).
 * Locked audio is {@code original[0 .. outputDuration]}.
 * <p>
 * When output is shorter than the source, {@code atrim}+{@code asetpts} plus AAC encode.
 * When output is within epsilon of the source, {@code -map 0:a:0?} and stream-copy if MP4-safe.
 */
public final class EditorAudioCodecPolicy {

	public static final String AAC_BITRATE = "192k";

	/**
	 * Codecs FFmpeg can stream-copy into an MP4 (ISO BMFF) container safely
	 * enough for V1 playback. Everything else is encoded to AAC.
	 */
	private static final Set<String> MP4_STREAM_COPY_SAFE = Set.of(
			"aac",
			"libfdk_aac",
			"libfaac",
			"mp4a",
			"mp3",
			"libmp3lame",
			"ac3",
			"eac3",
			"alac",
			"mp2"
	);

	private EditorAudioCodecPolicy() {
	}

	public enum Mode {
		COPY,
		AAC
	}

	/**
	 * @param sourceCodec ffprobe codec name (e.g. {@code aac}, {@code pcm_s16le});
	 *                    {@code null}/blank is treated as not copy-safe
	 */
	public static Mode forMp4(String sourceCodec) {
		String normalized = normalize(sourceCodec);
		if (normalized.isEmpty()) {
			return Mode.AAC;
		}
		if (MP4_STREAM_COPY_SAFE.contains(normalized)) {
			return Mode.COPY;
		}
		int dot = normalized.indexOf('.');
		if (dot > 0 && MP4_STREAM_COPY_SAFE.contains(normalized.substring(0, dot))) {
			return Mode.COPY;
		}
		return Mode.AAC;
	}

	public static boolean isMp4StreamCopySafe(String sourceCodec) {
		return forMp4(sourceCodec) == Mode.COPY;
	}

	/**
	 * Appends {@code -c:a copy} or {@code -c:a aac -b:a 192k}. No-op when the
	 * source has no audio stream. Audio filters force AAC.
	 */
	public static void appendMp4AudioArgs(List<String> command, boolean hasAudio, String sourceCodec) {
		appendMp4AudioArgs(command, hasAudio, sourceCodec, false);
	}

	public static void appendMp4AudioArgs(
			List<String> command,
			boolean hasAudio,
			String sourceCodec,
			boolean audioFiltered
	) {
		if (!hasAudio || command == null) {
			return;
		}
		command.add("-c:a");
		if (audioFiltered || !isMp4StreamCopySafe(sourceCodec)) {
			command.add("aac");
			command.add("-b:a");
			command.add(AAC_BITRATE);
			return;
		}
		command.add("copy");
	}

	private static String normalize(String sourceCodec) {
		if (sourceCodec == null || sourceCodec.isBlank()) {
			return "";
		}
		return sourceCodec.trim().toLowerCase(Locale.ROOT);
	}
}
