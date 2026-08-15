package com.vhmedia.livedownloader.editor;

/**
 * Locked original audio: {@code source[0 .. outputDuration]}. Never {@code atempo} / {@code asetrate}.
 * <p>
 * When output is shorter than the source (trim / visual speed-up), FFmpeg uses
 * {@code atrim} + {@code asetpts} and AAC encode — stream-copy cannot run through filters.
 * When output is within epsilon of the source duration, mapping {@code 0:a:0?} + {@code -c:a copy}
 * (if MP4-safe) is kept as a clean optimization.
 */
public final class EditorAudioExport {

	private EditorAudioExport() {
	}

	/**
	 * {@code true} when the locked audio must be filter-trimmed to {@code outputMillis}.
	 */
	public static boolean needsFilterTrim(
			boolean hasAudio,
			long outputMillis,
			long sourceMillis,
			int epsilonMillis
	) {
		if (!hasAudio || outputMillis <= 0L) {
			return false;
		}
		int epsilon = Math.max(0, epsilonMillis);
		if (sourceMillis <= 0L) {
			return true;
		}
		return outputMillis + epsilon < sourceMillis;
	}

	/**
	 * Locked audio chain. Start is always 0 on the original timeline.
	 */
	public static String trimFilter(long outputMillis) {
		return "[0:a]atrim=start=0:end="
				+ VisualReorderFilterGraph.seconds(outputMillis)
				+ ",asetpts=PTS-STARTPTS[aout]";
	}

	public static String mapLabel(boolean filterTrim) {
		return filterTrim ? "[aout]" : "0:a:0?";
	}
}
