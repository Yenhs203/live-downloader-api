package com.vhmedia.livedownloader.editor;

/**
 * Concrete x264 encode knobs for one {@link EditorExportQuality} tier.
 */
public record EditorEncodeProfile(String preset, int crf) {
}
