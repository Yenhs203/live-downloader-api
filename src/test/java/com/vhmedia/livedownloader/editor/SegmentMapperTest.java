package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.entity.VideoSegment;
import com.vhmedia.livedownloader.enums.EditorSegmentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentMapperTest {

	@Test
	void mapsVideoSegmentRoundTrip() {
		UUID projectId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		EditorSegment domain = EditorSegment.video("11111111-1111-1111-1111-111111111111", "B", 2_000, 4_000, assetId);

		VideoSegment entity = SegmentMapper.toEntity(projectId, UUID.randomUUID(), 1, domain);
		EditorSegment read = SegmentMapper.toDomain(entity);

		assertThat(entity.getProjectId()).isEqualTo(projectId);
		assertThat(entity.getAssetId()).isEqualTo(assetId);
		assertThat(entity.getType()).isEqualTo(EditorSegmentType.VIDEO);
		assertThat(entity.getPosition()).isEqualTo(1);
		assertThat(entity.getSourceStartMillis()).isEqualTo(2_000L);
		assertThat(entity.getSourceEndMillis()).isEqualTo(4_000L);
		assertThat(entity.getDurationMillis()).isEqualTo(2_000L);
		assertThat(entity.getPlaybackRate()).isEqualTo(1.0d);
		assertThat(read.label()).isEqualTo("B");
		assertThat(read.isVideo()).isTrue();
		assertThat(read.playbackRate()).isEqualTo(1.0d);
		assertThat(read.sourceStartMillis()).isEqualTo(2_000L);
		assertThat(read.sourceEndMillis()).isEqualTo(4_000L);
		assertThat(read.assetId()).isEqualTo(assetId);
	}

	@Test
	void mapsImageSegmentWithoutSourceRange() {
		UUID projectId = UUID.randomUUID();
		UUID imageAssetId = UUID.randomUUID();
		EditorSegment domain = EditorSegment.image(UUID.randomUUID().toString(), "IMG", imageAssetId, 5_000);

		VideoSegment entity = SegmentMapper.toEntity(projectId, UUID.randomUUID(), 0, domain);
		EditorSegment read = SegmentMapper.toDomain(entity);

		assertThat(entity.getType()).isEqualTo(EditorSegmentType.IMAGE);
		assertThat(entity.getAssetId()).isEqualTo(imageAssetId);
		assertThat(entity.getSourceStartMillis()).isNull();
		assertThat(entity.getSourceEndMillis()).isNull();
		assertThat(entity.getDurationMillis()).isEqualTo(5_000L);
		assertThat(read.isImage()).isTrue();
		assertThat(read.durationMillis()).isEqualTo(5_000L);
		assertThat(read.assetId()).isEqualTo(imageAssetId);
		assertThat(read.sourceStartMillis()).isNull();
	}

	@Test
	void mapsImageReplacementWithSourceSlot() {
		UUID projectId = UUID.randomUUID();
		UUID imageAssetId = UUID.randomUUID();
		EditorSegment domain = EditorSegment.image(
				UUID.randomUUID().toString(),
				"B",
				imageAssetId,
				10_000,
				10_000L,
				20_000L
		);

		VideoSegment entity = SegmentMapper.toEntity(projectId, UUID.randomUUID(), 1, domain);
		EditorSegment read = SegmentMapper.toDomain(entity);

		assertThat(entity.getType()).isEqualTo(EditorSegmentType.IMAGE);
		assertThat(entity.getSourceStartMillis()).isEqualTo(10_000L);
		assertThat(entity.getSourceEndMillis()).isEqualTo(20_000L);
		assertThat(entity.getDurationMillis()).isEqualTo(10_000L);
		assertThat(read.sourceStartMillis()).isEqualTo(10_000L);
		assertThat(read.durationMillis()).isEqualTo(10_000L);
	}

	@Test
	void preservesVisualOrder() {
		UUID projectId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "C", 25_000, 40_000, assetId),
				EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 10_000, assetId)
		);

		List<VideoSegment> rows = List.of(
				SegmentMapper.toEntity(projectId, assetId, 0, visual.get(0)),
				SegmentMapper.toEntity(projectId, assetId, 1, visual.get(1))
		);
		List<EditorSegment> read = SegmentMapper.toDomain(rows);

		assertThat(read).extracting(EditorSegment::label).containsExactly("C", "A");
		assertThat(read.stream().mapToLong(EditorSegment::durationMillis).sum()).isEqualTo(25_000);
	}

	@Test
	void mapsVideoPlaybackRateIntoVisualDuration() {
		UUID projectId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		EditorSegment domain = EditorSegment.video(
				UUID.randomUUID().toString(),
				"A",
				10_000,
				20_000,
				assetId,
				2.0d
		);

		VideoSegment entity = SegmentMapper.toEntity(projectId, assetId, 0, domain);
		EditorSegment read = SegmentMapper.toDomain(entity);

		assertThat(entity.getSourceStartMillis()).isEqualTo(10_000L);
		assertThat(entity.getSourceEndMillis()).isEqualTo(20_000L);
		assertThat(entity.getDurationMillis()).isEqualTo(5_000L);
		assertThat(entity.getPlaybackRate()).isEqualTo(2.0d);
		assertThat(read.durationMillis()).isEqualTo(5_000L);
		assertThat(read.playbackRate()).isEqualTo(2.0d);
		assertThat(read.sourceDurationMillis()).isEqualTo(10_000L);
	}
}
