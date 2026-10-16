package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;

class SchedulingConfigurationTest {
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(SchedulingConfiguration.class, ScheduleService.class, EngineMapper.class);

	@Test
	void nodeCapDefaultsFromApplicationProperties() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(SchedulingProperties.class).nodeCap()).isEqualTo(1000000);
		});
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 12345})
	void configuredCapIsPassedToEngine(int cap) {
		var catalog = mock(CourseCatalog.class);
		var sections = mock(SectionCatalog.class);
		var engine = mock(SchedulingEngine.class);
		when(catalog.termExists("2027-SPRING")).thenReturn(true);
		when(catalog.findCourse("2027-SPRING", "CSE-017")).thenReturn(Optional.of(new CourseSummary("CSE-017", "Programming", 3)));
		when(sections.sectionsFor(any(), any())).thenReturn(List.of());
		when(engine.solve(any(), anyInt())).thenReturn(new SchedulingEngine.Result(List.of(), List.of(), true));
		runner.withPropertyValues("scheduling.node-cap=" + cap)
			.withBean(CourseCatalog.class, () -> catalog)
			.withBean(SectionCatalog.class, () -> sections)
			.withBean(SchedulingEngine.class, () -> engine)
			.run(context -> {
				assertThat(context).hasNotFailed();
				var request = new GenerateScheduleRequest("2027-SPRING",
					new SchedulePreferences(new HardConstraints(List.of("CSE-017"), 1, 21, List.of()), null), null, null);
				var response = context.getBean(ScheduleService.class).generate(request);
				assertThat(response.searchLimitReached()).isTrue();
				verify(engine).solve(any(), eq(cap));
			});
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "abc", "1.5", "2147483648"})
	void invalidNodeCapFailsStartup(String value) {
		runner.withPropertyValues("scheduling.node-cap=" + value).run(context -> assertThat(context).hasFailed());
	}

	@Test
	void absentSolverWithAnAvailableCatalogStillReturns503() {
		var catalog = mock(CourseCatalog.class);
		var sections = mock(SectionCatalog.class);
		when(catalog.termExists("2027-SPRING")).thenReturn(true);
		when(catalog.findCourse("2027-SPRING", "CSE-017")).thenReturn(Optional.of(new CourseSummary("CSE-017", "Programming", 3)));
		runner.withBean(CourseCatalog.class, () -> catalog).withBean(SectionCatalog.class, () -> sections)
			.run(context -> {
				assertThat(context).hasNotFailed();
				var request = new GenerateScheduleRequest("2027-SPRING",
					new SchedulePreferences(new HardConstraints(List.of("CSE-017"), 1, 21, List.of()), null), null, null);
				assertThatThrownBy(() -> context.getBean(ScheduleService.class).generate(request))
					.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
				verifyNoInteractions(sections);
			});
	}
}
