package com.vhmedia.livedownloader.editor;

/**
 * Resolved export canvas/fps used both for frontend preview and FFmpeg.
 * {@code outputFps} is visual-only. Locked audio is {@code original[0..outputDuration]} outside
 * (or appended to) the visual graph — never {@code atempo}.
 */
public record EditorExportPlan(
		EditorExportSettings settings,
		Integer outputWidth,
		Integer outputHeight,
		Double outputFps,
		boolean scale,
		boolean changeFps
) {
}
