package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.DummyCourseCatalog;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * The Maven test profile uses test-only catalog fixtures and requires no database.
 * The packaged application instead defaults to the authoritative database catalog.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefaultModeStartsWithoutDatabaseTest {

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc mvc;

	@Test
	void theDummyCatalogIsActiveAndNoDatabaseObjectExists() {
		assertThat(context.getBeansOfType(CourseCatalog.class)).hasSize(1).containsOnlyKeys("dummyCourseCatalog");
		assertThat(context.getBean(CourseCatalog.class)).isInstanceOf(DummyCourseCatalog.class);
		assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
		// The default mode has demo sections (DummySectionCatalog) but still no database object.
		assertThat(context.getBeansOfType(SectionCatalog.class)).hasSize(1).containsOnlyKeys("dummySectionCatalog");
		assertThat(context.getBeansOfType(JdbcCourseCatalog.class)).isEmpty();
		assertThat(context.getBeansOfType(JdbcSectionCatalog.class)).isEmpty();
	}

	@Test
	void theApplicationServesRequests() throws Exception {
		mvc.perform(get("/api/health")).andExpect(status().isOk());
	}

}
