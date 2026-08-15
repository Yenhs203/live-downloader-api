package com.vhmedia.livedownloader.editor;

import org.springframework.stereotype.Component;

/**
 * Maps source geometry + V1 export presets to an even-dimension H.264 canvas.
 * Audio duration/timeline is independent of this plan — FPS/resolution never change audio speed.
 * <p>
 * Presets never <strong>upscale</strong>: if the source is already smaller than 1080p/720p/540p,
 * the output canvas stays the even source size.
 */
@Component
public class EditorExportPlanner {

	public EditorExportPlan plan(Integer sourceWidth, Integer sourceHeight, Double sourceFps, EditorExportSettings settings) {
		EditorExportSettings normalized = settings == null ? EditorExportSettings.defaults() : settings.normalized();
		Integer sourceW = evenOrNull(sourceWidth);
		Integer sourceH = evenOrNull(sourceHeight);
		Integer width = sourceW;
		Integer height = sourceH;

		if (!normalized.resolution().isOriginal() && sourceW != null && sourceH != null) {
			int[] target = canvasFor(sourceW, sourceH, normalized.resolution().shortSide());
			width = Math.min(sourceW, target[0]);
			height = Math.min(sourceH, target[1]);
		}

		boolean scale = needsScale(sourceWidth, sourceHeight, sourceW, sourceH, width, height);
		boolean changeFps = !normalized.fps().isOriginal();
		Double outputFps = changeFps ? normalized.fps().fps() : sourceFps;

		return new EditorExportPlan(normalized, width, height, outputFps, scale, changeFps);
	}

	/**
	 * Orientation-preserving 16:9 canvas from the preset short side.
	 * Landscape 1080p → 1920×1080; portrait 1080p → 1080×1920; same pattern for 720p and 540p.
	 */
	static int[] canvasFor(int sourceWidth, int sourceHeight, int shortSide) {
		int longSide = even(shortSide * 16 / 9);
		int evenShort = even(shortSide);
		if (sourceWidth > sourceHeight) {
			return new int[] {longSide, evenShort};
		}
		if (sourceHeight > sourceWidth) {
			return new int[] {evenShort, longSide};
		}
		return new int[] {evenShort, evenShort};
	}

	private static boolean needsScale(
			Integer rawWidth,
			Integer rawHeight,
			Integer evenSourceW,
			Integer evenSourceH,
			Integer outputW,
			Integer outputH
	) {
		if (outputW == null || outputH == null || evenSourceW == null || evenSourceH == null) {
			return false;
		}
		boolean oddSource = (rawWidth != null && rawWidth % 2 != 0) || (rawHeight != null && rawHeight % 2 != 0);
		return oddSource || !outputW.equals(evenSourceW) || !outputH.equals(evenSourceH);
	}

	static Integer evenOrNull(Integer value) {
		if (value == null || value <= 0) {
			return null;
		}
		return even(value);
	}

	static int even(int value) {
		int normalized = Math.max(2, value);
		return normalized - (normalized % 2);
	}
}
