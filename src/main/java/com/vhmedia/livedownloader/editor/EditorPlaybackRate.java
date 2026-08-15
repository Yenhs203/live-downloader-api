package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.exception.InvalidPlaybackRateException;

import java.util.List;
import java.util.Locale;

/**
 * Discrete clip playback rates. Visual duration is {@code sourceDuration / rate};
 * original audio stays {@code source[0..outputDuration]} at 1.0x.
 */
public final class EditorPlaybackRate {

	public static final double DEFAULT = 1.0d;

	public static final List<Double> ALLOWED = List.of(
			0.25d, 0.5d, 0.75d, 1.0d, 1.25d, 1.5d, 2.0d, 3.0d, 4.0d
	);

	private EditorPlaybackRate() {
	}

	public static double normalize(Double value) {
		if (value == null || !Double.isFinite(value) || value <= 0.0d) {
			return DEFAULT;
		}
		for (Double allowed : ALLOWED) {
			if (Math.abs(allowed - value) < 1.0e-6d) {
				return allowed;
			}
		}
		throw new InvalidPlaybackRateException("Unsupported playback rate: " + value);
	}

	/** @see #normalize(Double) */
	public static double canonicalize(Double value) {
		return normalize(value);
	}

	public static boolean isUnity(double rate) {
		return Math.abs(normalize(rate) - DEFAULT) < 1.0e-6d;
	}

	public static boolean same(double left, double right) {
		return Math.abs(normalize(left) - normalize(right)) < 1.0e-6d;
	}

	public static String ffmpegLiteral(double rate) {
		return String.format(Locale.US, "%.3f", normalize(rate));
	}

	/** FFmpeg {@code setpts} expression. Unity keeps {@code PTS-STARTPTS}. */
	public static String setpts(double rate) {
		double normalized = normalize(rate);
		if (isUnity(normalized)) {
			return "setpts=PTS-STARTPTS";
		}
		return "setpts=(PTS-STARTPTS)/" + ffmpegLiteral(normalized);
	}

	public static long visualDurationMillis(long sourceDurationMillis, double rate) {
		if (sourceDurationMillis <= 0L) {
			return 0L;
		}
		double normalized = normalize(rate);
		if (isUnity(normalized)) {
			return sourceDurationMillis;
		}
		return Math.max(0L, Math.round(sourceDurationMillis / normalized));
	}
}
