package io.github.michael04.registration.course;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The production Course API must return a safe 503, with no synthetic fallback, when PostgreSQL is down. */
@SpringBootTest(properties = {"app.catalog.source=database",
	"app.catalog.database.url=jdbc:postgresql://127.0.0.1:1/catalog-down-test",
	"app.catalog.database.username=reader-test-user", "app.catalog.database.connection-timeout=300ms"})
@AutoConfigureMockMvc
class CourseUnavailableTest {
	@Autowired MockMvc mvc;

	@ParameterizedTest
	@ValueSource(strings = {"/api/terms", "/api/courses?term=2027-SPRING", "/api/courses/CSE-007?term=2027-SPRING"})
	void unavailableDatabaseReturns503(String url) throws Exception {
		mvc.perform(get(url)).andExpect(status().isServiceUnavailable())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"));
	}
}
