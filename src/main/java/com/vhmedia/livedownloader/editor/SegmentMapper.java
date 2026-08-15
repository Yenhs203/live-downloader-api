package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.entity.VideoSegment;
import com.vhmedia.livedownloader.enums.EditorSegmentType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SegmentMapper {

	private SegmentMapper() {
	}

	public static EditorSegment toDomain(VideoSegment row) {
		if (row.getType() == EditorSegmentType.IMAGE) {
			return EditorSegment.image(
					row.getId().toString(),
					row.getLabel(),
					row.getAssetId(),
					row.getDurationMillis(),
					row.getSourceStartMillis(),
					row.getSourceEndMillis()
			);
		}
		long start = row.getSourceStartMillis() == null ? 0L : row.getSourceStartMillis();
		long end = row.getSourceEndMillis() == null ? start + row.getDurationMillis() : row.getSourceEndMillis();
		return EditorSegment.video(
				row.getId().toString(),
				row.getLabel(),
				start,
				end,
				row.getAssetId(),
				row.getPlaybackRate()
		);
	}

	public static List<EditorSegment> toDomain(List<VideoSegment> rows) {
		List<EditorSegment> visual = new ArrayList<>(rows.size());
		for (VideoSegment row : rows) {
			visual.add(toDomain(row));
		}
		return visual;
	}

	public static VideoSegment toEntity(UUID projectId, UUID fallbackVideoAssetId, int position, EditorSegment domain) {
		UUID id;
		try {
			id = UUID.fromString(domain.id());
		} catch (RuntimeException ex) {
			id = UUID.randomUUID();
		}
		UUID assetId = domain.assetId() != null ? domain.assetId() : fallbackVideoAssetId;
		return VideoSegment.builder()
				.id(id)
				.projectId(projectId)
				.assetId(assetId)
				.type(domain.type())
				.label(domain.label())
				.sourceStartMillis(domain.sourceStartMillis())
				.sourceEndMillis(domain.sourceEndMillis())
				.durationMillis(domain.durationMillis())
				.playbackRate(domain.playbackRate())
				.position(position)
				.build();
	}
}
