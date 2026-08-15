package com.vhmedia.livedownloader.repository;

import com.vhmedia.livedownloader.entity.VideoProject;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VideoProjectRepository extends JpaRepository<VideoProject, UUID> {

	Page<VideoProject> findByStatusNot(ProjectStatus status, Pageable pageable);

	Page<VideoProject> findByStatus(ProjectStatus status, Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM VideoProject p WHERE p.id = :id")
	Optional<VideoProject> findByIdForUpdate(@Param("id") UUID id);
}
