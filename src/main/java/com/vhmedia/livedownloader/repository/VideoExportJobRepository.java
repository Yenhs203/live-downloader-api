package com.vhmedia.livedownloader.repository;

import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.enums.ExportStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoExportJobRepository extends JpaRepository<VideoExportJob, UUID> {

	Optional<VideoExportJob> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);

	List<VideoExportJob> findByStatusIn(Collection<ExportStatus> statuses);

	boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<ExportStatus> statuses);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT j FROM VideoExportJob j WHERE j.id = :id")
	Optional<VideoExportJob> findByIdForUpdate(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT j FROM VideoExportJob j WHERE j.projectId = :projectId ORDER BY j.createdAt DESC")
	List<VideoExportJob> findByProjectIdForUpdate(@Param("projectId") UUID projectId);
}
