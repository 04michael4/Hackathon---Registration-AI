package io.github.michael04.registration.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.schedule.ScheduleService;

/**
 * Registers the demo catalog only when {@code app.catalog.source=demo} (env {@code CATALOG_SOURCE=demo}). In every other mode
 * nothing here exists, so the default PostgreSQL mode and the test modes are unaffected.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "demo")
public class DemoConfiguration {

	/** One bean serving both {@code CourseCatalog} and {@code SectionCatalog}. */
	@Bean
	DemoCatalog demoCatalog(@Value("${app.demo.fixture-path:}") String fixturePath) {
		return new DemoCatalog(DemoFixtureLoader.load(fixturePath));
	}

	@Bean
	DemoScheduleService demoScheduleService(DemoCatalog catalog, ScheduleService scheduleService) {
		return new DemoScheduleService(catalog, scheduleService);
	}

}
