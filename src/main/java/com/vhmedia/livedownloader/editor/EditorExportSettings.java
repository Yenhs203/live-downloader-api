package com.vhmedia.livedownloader.editor;

/**
 * Persisted export document. V1 fields are fps/resolution/codec/quality.
 * Audio Locked: {@code original[0..outputDuration]} (not reordered/sped; trim shortens it).
 */
public record EditorExportSettings(
		EditorExportFps fps,
		EditorExportResolution resolution,
		EditorExportCodec codec,
		EditorExportQuality quality,
		boolean keepOriginalAudio
) {

	public EditorExportSettings(EditorExportFps fps, EditorExportResolution resolution, EditorExportCodec codec) {
		this(fps, resolution, codec, EditorExportQuality.BALANCED, true);
	}

	public static EditorExportSettings defaults() {
		return new EditorExportSettings(
				EditorExportFps.ORIGINAL,
				EditorExportResolution.ORIGINAL,
				EditorExportCodec.H264,
				EditorExportQuality.BALANCED,
				true
		);
	}

	public EditorExportSettings normalized() {
		return new EditorExportSettings(
				fps != null ? fps : EditorExportFps.ORIGINAL,
				resolution != null ? resolution : EditorExportResolution.ORIGINAL,
				codec != null ? codec : EditorExportCodec.H264,
				quality != null ? quality : EditorExportQuality.BALANCED,
				keepOriginalAudio
		);
	}
}
