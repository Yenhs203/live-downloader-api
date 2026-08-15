package com.vhmedia.livedownloader.repository;

import com.vhmedia.livedownloader.entity.VideoSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VideoSegmentRepository extends JpaRepository<VideoSegment, UUID> {

	List<VideoSegment> findByProjectIdOrderByPositionAsc(UUID projectId);

	boolean existsByProjectIdAndAssetId(UUID projectId, UUID assetId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from VideoSegment s where s.projectId = :projectId")
	void deleteByProjectId(@Param("projectId") UUID projectId);
}
