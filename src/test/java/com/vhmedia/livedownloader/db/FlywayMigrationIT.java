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
	}
}
