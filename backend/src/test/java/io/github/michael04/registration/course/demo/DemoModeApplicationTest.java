package io.github.michael04.registration.course.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The whole app in opt-in demo mode: no database, real engine, made-up catalog. */
@SpringBootTest(properties = "app.catalog.source=demo")
@AutoConfigureMockMvc
class DemoModeApplicationTest {

	@Autowired MockMvc mvc;
	@Autowired ApplicationContext context;

	@Test
	void demoModeNeedsNoDatabase() {
		assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
	}

	@Test
	void termsSearchAndDetailWork() throws Exception {
		mvc.perform(get("/api/terms")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].term").value("2027-SPRING"));
		mvc.perform(get("/api/courses").param("term", "2027-SPRING").param("q", "calculus"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
		mvc.perform(get("/api/courses/CSE-109").param("term", "2027-SPRING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sections[0].instructors[0]").value("Dr. Lena Fischer"));
	}

	@Test
	void generateUsesTheRealEngineAndExplainsAnEightAmOnlyCourse() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content("""
			{"term":"2027-SPRING","maxResults":3,"preferences":{"hardConstraints":{
			  "requiredCourseIds":["CSE-017","CSE-109"],"minCredits":1,"maxCredits":18,
			  "blockedTimes":[{"days":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],"start":"08:00","end":"09:00"}]}}}
			"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules").isEmpty())
			.andExpect(jsonPath("$.diagnostics", hasItem("CSE-109: every section overlaps your blocked times.")));
	}

}
