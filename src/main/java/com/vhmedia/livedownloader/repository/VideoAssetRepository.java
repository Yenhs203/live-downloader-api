package com.vhmedia.livedownloader.repository;

import com.vhmedia.livedownloader.entity.VideoAsset;
import com.vhmedia.livedownloader.enums.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoAssetRepository extends JpaRepository<VideoAsset, UUID> {

	List<VideoAsset> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

	Optional<VideoAsset> findByIdAndProjectId(UUID id, UUID projectId);

	Optional<VideoAsset> findByProjectIdAndPrimarySourceTrue(UUID projectId);

	long countByProjectIdAndType(UUID projectId, AssetType type);

	void deleteByProjectId(UUID projectId);
}
