package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;

/**
 * OPT-IN (RUN_LIVE_IT=true, same database as {@link LiveCatalogIntegrationTest}): with the DEFAULT schema
 * ({@code catalog_api}) promoted courses are invisible. Promotion changes nothing for anyone who has not opted in
 * with {@code DB_CATALOG_SCHEMA=catalog_live}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_IT", matches = "true")
@SpringBootTest(properties = { "app.catalog.source=database", "app.catalog.database.url=${DB_URL}",
		"app.catalog.database.username=${DB_USERNAME}", "app.catalog.database.password=${DB_PASSWORD:}" })
@AutoConfigureMockMvc
class DefaultSchemaHidesPromotedCoursesIntegrationTest {

	@Autowired
	CourseCatalog courses;

	@Autowired
	MockMvc mvc;

	@Test
	void onlyTheVerifiedSnapshotIsOfferedByDefault() {
		assertThat(courses.listCourses("2027-SPRING")).extracting(CourseSummary::courseId).containsExactly("CSE-007");
	}

	@Test
	void aPromotedCourseIsUnknownUnderTheDefaultSchema() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(
				"{\"term\":\"2027-SPRING\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"CSE-092\"],\"minCredits\":3,\"maxCredits\":3}}}"))
			.andExpect(status().isUnprocessableContent());
	}

}
