package com.vhmedia.livedownloader.db;

import com.vhmedia.livedownloader.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@EnabledIfDockerAvailable
class FlywayMigrationIT {

	@Autowired
	private DataSource dataSource;

	@Test
	void appliesV1LiveDownloadJobMigration() {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		Integer tableCount = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.tables
						WHERE table_schema = 'public'
						  AND table_name = 'live_download_job'
						""",
				Integer.class
		);
		assertThat(tableCount).isEqualTo(1);

		Integer flywayCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '1'",
				Integer.class
		);
		assertThat(flywayCount).isEqualTo(1);

		Integer statusIndex = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM pg_indexes
						WHERE schemaname = 'public'
						  AND tablename = 'live_download_job'
						  AND indexname = 'idx_live_download_job_status'
						""",
				Integer.class
		);
		assertThat(statusIndex).isEqualTo(1);

		Integer uniqueBaseName = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.table_constraints
						WHERE table_schema = 'public'
						  AND table_name = 'live_download_job'
						  AND constraint_type = 'UNIQUE'
						  AND constraint_name = 'uq_live_download_job_output_base_name'
						""",
				Integer.class
		);
		assertThat(uniqueBaseName).isEqualTo(1);

		Integer legacyEditorTableCount = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.tables
						WHERE table_schema = 'public'
						  AND table_name IN ('video_edit_project', 'video_edit_asset')
						""",
				Integer.class
		);
		assertThat(legacyEditorTableCount).isZero();

		Integer flywayV2 = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '2'",
				Integer.class
		);
		assertThat(flywayV2).isEqualTo(1);

		Integer flywayV5 = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '5'",
				Integer.class
		);
		assertThat(flywayV5).isEqualTo(1);

		Integer videoProjectCount = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.tables
						WHERE table_schema = 'public'
						  AND table_name = 'video_project'
						""",
				Integer.class
		);
		assertThat(videoProjectCount).isEqualTo(1);

		Integer editorDomainTables = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.tables
						WHERE table_schema = 'public'
						  AND table_name IN ('video_project', 'video_asset', 'video_segment', 'video_export_job')
						""",
				Integer.class
		);
		Integer flywayV6 = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '6'",
				Integer.class
		);
		assertThat(flywayV6).isEqualTo(1);

		Integer flywayV7 = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '7'",
				Integer.class
		);
		assertThat(flywayV7).isEqualTo(1);

		Integer exportQuality = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.columns
						WHERE table_schema = 'public'
						  AND table_name = 'video_export_job'
						  AND column_name = 'quality'
						""",
				Integer.class
		);
		assertThat(exportQuality).isEqualTo(1);

		Integer exportProjectIndex = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM pg_indexes
						WHERE schemaname = 'public'
						  AND tablename = 'video_export_job'
						  AND indexname = 'idx_video_export_job_project_id'
						""",
				Integer.class
		);
		assertThat(exportProjectIndex).isEqualTo(1);

		Integer storageFileName = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.columns
						WHERE table_schema = 'public'
						  AND table_name = 'video_asset'
						  AND column_name = 'storage_file_name'
						""",
				Integer.class
		);
		assertThat(storageFileName).isEqualTo(1);

		Integer flywayV8 = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '8'",
				Integer.class
		);
		assertThat(flywayV8).isEqualTo(1);

		Integer sourceStorageMode = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.columns
						WHERE table_schema = 'public'
						  AND table_name = 'video_project'
						  AND column_name = 'source_storage_mode'
						""",
				Integer.class
		);
		assertThat(sourceStorageMode).isEqualTo(1);

		Integer flywayV9 = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '9'",
				Integer.class
		);
		assertThat(flywayV9).isEqualTo(1);

		Integer playbackRate = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.columns
						WHERE table_schema = 'public'
						  AND table_name = 'video_segment'
						  AND column_name = 'playback_rate'
						""",
				Integer.class
		);
		assertThat(playbackRate).isEqualTo(1);

		Integer flywayV10 = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '10'",
				Integer.class
		);
		assertThat(flywayV10).isEqualTo(1);

		Integer playbackRateCheck = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM pg_constraint
						WHERE conname = 'ck_video_segment_playback_rate'
						""",
				Integer.class
		);
		assertThat(playbackRateCheck).isEqualTo(1);

		Integer flywayV11 = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '11'",
				Integer.class
		);
		assertThat(flywayV11).isEqualTo(1);

		Integer timelineVersion = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.columns
						WHERE table_schema = 'public'
						  AND table_name = 'video_project'
						  AND column_name = 'timeline_version'
						""",
				Integer.class
		);
		assertThat(timelineVersion).isEqualTo(1);

		Integer originalSourceStart = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.columns
						WHERE table_schema = 'public'
						  AND table_name = 'video_segment'
						  AND column_name = 'original_source_start_millis'
						""",
				Integer.class
		);
		assertThat(originalSourceStart).isZero();

		Integer projectOutputDuration = jdbc.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.columns
						WHERE table_schema = 'public'
						  AND table_name = 'video_project'
						  AND column_name IN ('output_duration_millis', 'source_duration_millis')
						""",
				Integer.class
		);
		assertThat(projectOutputDuration).isZero();
	}
}
