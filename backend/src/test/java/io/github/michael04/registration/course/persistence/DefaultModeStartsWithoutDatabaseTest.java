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
 * With the default settings the app must start with no database at all, even though the PostgreSQL driver, HikariCP and
 * spring-jdbc are now on the classpath. (Spring Boot's own JDBC starter would have broken this.)
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
		assertThat(context.getBeansOfType(SectionCatalog.class)).isEmpty();
		assertThat(context.getBeansOfType(JdbcCourseCatalog.class)).isEmpty();
		assertThat(context.getBeansOfType(JdbcSectionCatalog.class)).isEmpty();
	}

	@Test
	void theApplicationServesRequests() throws Exception {
		mvc.perform(get("/api/health")).andExpect(status().isOk());
	}

}
