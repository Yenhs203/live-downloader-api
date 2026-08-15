package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.exception.InvalidOutputDurationException;
import com.vhmedia.livedownloader.exception.OutputDurationExceedsAudioException;

import java.util.List;

/**
 * Single place for editor timeline duration math.
 * <ul>
 *   <li>{@code sourceDurationMillis} — probed source file length (DB {@code video_project.duration_millis}).</li>
 *   <li>{@code outputDurationMillis} — {@code sum(visual segment durations after speed)}; export {@code -t} and audio length.</li>
 * </ul>
 */
public final class EditorTimelineDurations {

	private EditorTimelineDurations() {
	}

	public static long sourceDurationMillis(Long projectSourceDurationMillis) {
		return projectSourceDurationMillis == null || projectSourceDurationMillis < 0L
				? 0L
				: projectSourceDurationMillis;
	}

	/**
	 * Output / editor timeline length. Audio is {@code original[0 .. outputDuration]}.
	 * This is <em>not</em> required to equal {@code sourceDurationMillis} (trim and speed change it).
	 */
	public static long outputDurationMillis(List<EditorSegment> visualOrder) {
		if (visualOrder == null || visualOrder.isEmpty()) {
			return 0L;
		}
		long total = 0L;
		for (EditorSegment segment : visualOrder) {
			total += segment.durationMillis();
		}
		return total;
	}

	public static void assertPositive(long outputMillis) {
		if (outputMillis <= 0L) {
			throw new InvalidOutputDurationException("Output duration must be greater than 0");
		}
	}

	/**
	 * V1 locked-audio policy: do not loop or pad silence. Slow-motion may not make
	 * output longer than the original audio stream (project/audio limit).
	 */
	public static void assertFitsLockedAudio(
			boolean hasAudio,
			Long sourceDurationMillis,
			List<EditorSegment> visualOrder
	) {
		long output = outputDurationMillis(visualOrder);
		assertPositive(output);
		if (!hasAudio) {
			return;
		}
		long available = sourceDurationMillis(sourceDurationMillis);
		if (output > available) {
			throw new OutputDurationExceedsAudioException(
					"Output duration (" + output + " ms) cannot exceed the original audio ("
							+ available + " ms). Speed up, trim, or rearrange clips instead."
			);
		}
	}
}
