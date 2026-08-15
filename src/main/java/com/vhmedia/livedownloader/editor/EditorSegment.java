package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.enums.EditorSegmentType;

import java.util.UUID;

/**
 * One visual clip in output order.
 * <ul>
 *   <li>{@link EditorSegmentType#VIDEO} — slice of the source timeline
 *       ({@code sourceStartMillis}..{@code sourceEndMillis}) played at {@code playbackRate}.</li>
 *   <li>{@link EditorSegmentType#IMAGE} — still asset looped for {@code durationMillis}.
 *       {@code assetId} identifies an uploaded project asset. Rate is always 1.0.</li>
 * </ul>
 * Audio is never represented here. Original audio stays {@code source[0..outputDuration]}.
 * <p>
 * Split keeps the left clip id so {@code merge-next} is a deterministic undo. Trim/speed mutate
 * the current source range and rate in place — original bounds are not stored (no false reset).
 */
public record EditorSegment(
		String id,
		String label,
		EditorSegmentType type,
		Long sourceStartMillis,
		Long sourceEndMillis,
		UUID assetId,
		Long declaredDurationMillis,
		double playbackRate
) {

	public EditorSegment {
		if (type == null) {
			type = EditorSegmentType.VIDEO;
		}
		if (playbackRate <= 0.0d) {
			playbackRate = EditorPlaybackRate.DEFAULT;
		}
	}

	public EditorSegment(String id, String label, long sourceStartMillis, long sourceEndMillis) {
		this(
				id,
				label,
				EditorSegmentType.VIDEO,
				sourceStartMillis,
				sourceEndMillis,
				null,
				sourceEndMillis - sourceStartMillis,
				EditorPlaybackRate.DEFAULT
		);
	}

	public static EditorSegment video(String id, String label, long sourceStartMillis, long sourceEndMillis) {
		return video(id, label, sourceStartMillis, sourceEndMillis, null, EditorPlaybackRate.DEFAULT);
	}

	public static EditorSegment video(
			String id,
			String label,
			long sourceStartMillis,
			long sourceEndMillis,
			UUID assetId
	) {
		return video(id, label, sourceStartMillis, sourceEndMillis, assetId, EditorPlaybackRate.DEFAULT);
	}

	public static EditorSegment video(
			String id,
			String label,
			long sourceStartMillis,
			long sourceEndMillis,
			UUID assetId,
			double playbackRate
	) {
		double rate = EditorPlaybackRate.canonicalize(playbackRate);
		long source = sourceEndMillis - sourceStartMillis;
		return new EditorSegment(
				id,
				label,
				EditorSegmentType.VIDEO,
				sourceStartMillis,
				sourceEndMillis,
				assetId,
				source,
				rate
		);
	}

	public static EditorSegment image(String id, String label, UUID assetId, long durationMillis) {
		return image(id, label, assetId, durationMillis, null, null);
	}

	/**
	 * IMAGE occupying a former VIDEO slot. {@code sourceStart}/{@code sourceEnd} are kept so
	 * {@code POST .../reset} can restore the original VIDEO clip for that range.
	 */
	public static EditorSegment image(
			String id,
			String label,
			UUID assetId,
			long durationMillis,
			Long sourceStartMillis,
			Long sourceEndMillis
	) {
		return new EditorSegment(
				id,
				label,
				EditorSegmentType.IMAGE,
				sourceStartMillis,
				sourceEndMillis,
				assetId,
				durationMillis,
				EditorPlaybackRate.DEFAULT
		);
	}

	public EditorSegment withPlaybackRate(double playbackRate) {
		if (isImage()) {
			return this;
		}
		return video(id, label, sourceStartMillis, sourceEndMillis, assetId, playbackRate);
	}

	public boolean isVideo() {
		return type() != EditorSegmentType.IMAGE;
	}

	public boolean isImage() {
		return type() == EditorSegmentType.IMAGE;
	}

	public boolean hasSourceSlot() {
		return sourceStartMillis != null && sourceEndMillis != null && sourceEndMillis > sourceStartMillis;
	}

	public long sourceDurationMillis() {
		if (!hasSourceSlot()) {
			return 0L;
		}
		return sourceEndMillis - sourceStartMillis;
	}

	/**
	 * Visual duration of this clip (after speed for VIDEO).
	 */
	public long durationMillis() {
		if (isImage()) {
			return declaredDurationMillis == null ? 0L : declaredDurationMillis;
		}
		return EditorPlaybackRate.visualDurationMillis(sourceDurationMillis(), playbackRate);
	}
}
