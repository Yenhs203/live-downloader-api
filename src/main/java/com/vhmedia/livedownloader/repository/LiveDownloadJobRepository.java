package com.vhmedia.livedownloader.repository;

import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveDownloadJobRepository extends JpaRepository<LiveDownloadJob, UUID> {

	Optional<LiveDownloadJob> findByOutputBaseName(String outputBaseName);

	List<LiveDownloadJob> findByStatus(LiveJobStatus status);

	List<LiveDownloadJob> findByStatusIn(Collection<LiveJobStatus> statuses);

	Page<LiveDownloadJob> findByStatusIn(Collection<LiveJobStatus> statuses, Pageable pageable);

	Page<LiveDownloadJob> findByStatusNot(LiveJobStatus status, Pageable pageable);

	Page<LiveDownloadJob> findByStatus(LiveJobStatus status, Pageable pageable);

	long countByStatus(LiveJobStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT j FROM LiveDownloadJob j WHERE j.id = :id")
	Optional<LiveDownloadJob> findByIdForUpdate(@Param("id") UUID id);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE LiveDownloadJob j
			SET j.status = :toStatus,
			    j.stoppedAt = COALESCE(j.stoppedAt, :now),
			    j.updatedAt = :now
			WHERE j.id = :id
			  AND j.status = :fromStatus
			""")
	int transitionStatus(
			@Param("id") UUID id,
			@Param("fromStatus") LiveJobStatus fromStatus,
			@Param("toStatus") LiveJobStatus toStatus,
			@Param("now") Instant now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE LiveDownloadJob j
			SET j.status = :newStatus,
			    j.errorMessage = :errorMessage,
			    j.updatedAt = :updatedAt
			WHERE j.status IN :activeStatuses
			""")
	int markInterrupted(
			@Param("newStatus") LiveJobStatus newStatus,
			@Param("errorMessage") String errorMessage,
			@Param("updatedAt") Instant updatedAt,
			@Param("activeStatuses") Collection<LiveJobStatus> activeStatuses
	);
}
