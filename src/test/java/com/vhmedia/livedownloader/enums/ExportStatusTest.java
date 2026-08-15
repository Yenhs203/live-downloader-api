package com.vhmedia.livedownloader.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportStatusTest {

	@Test
	void activeStatusesAreInProgressExportSteps() {
		assertThat(ExportStatus.CREATED.isActive()).isTrue();
		assertThat(ExportStatus.PREPARING.isActive()).isTrue();
		assertThat(ExportStatus.RENDERING.isActive()).isTrue();
		assertThat(ExportStatus.FINALIZING.isActive()).isTrue();
		assertThat(ExportStatus.COMPLETED.isActive()).isFalse();
		assertThat(ExportStatus.FAILED.isActive()).isFalse();
		assertThat(ExportStatus.CANCELLED.isActive()).isFalse();
	}

	@Test
	void terminalStatusesCannotTransitionFurther() {
		assertThat(ExportStatus.COMPLETED.isTerminal()).isTrue();
		assertThat(ExportStatus.FAILED.isTerminal()).isTrue();
		assertThat(ExportStatus.CANCELLED.isTerminal()).isTrue();
		assertThat(ExportStatus.CREATED.isTerminal()).isFalse();
		assertThat(ExportStatus.PREPARING.isTerminal()).isFalse();
		assertThat(ExportStatus.RENDERING.isTerminal()).isFalse();
		assertThat(ExportStatus.FINALIZING.isTerminal()).isFalse();
	}

	@Test
	void everyStatusIsEitherActiveOrTerminal() {
		for (ExportStatus status : ExportStatus.values()) {
			assertThat(status.isActive() ^ status.isTerminal())
					.as("%s should be active XOR terminal", status)
					.isTrue();
		}
	}
}
