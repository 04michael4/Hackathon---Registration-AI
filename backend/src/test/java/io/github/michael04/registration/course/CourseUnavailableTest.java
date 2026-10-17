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

/** The AI development dummy must never supply invented production Course API responses. */
@SpringBootTest(properties = "app.catalog.source=dummy")
@AutoConfigureMockMvc
class CourseUnavailableTest {
	@Autowired MockMvc mvc;

	@ParameterizedTest
	@ValueSource(strings = {"/api/terms", "/api/courses?term=202710", "/api/courses/CSE-007?term=202710"})
	void unavailableDatabaseReturns503(String url) throws Exception {
		mvc.perform(get(url)).andExpect(status().isServiceUnavailable())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"));
	}
}
