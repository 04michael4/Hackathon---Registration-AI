package io.github.michael04.registration.schedule;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CatalogSection.Status;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * Full application with the real engine adapter and an in-memory test catalog: request validation,
 * catalog lookup, closed-section handling, the native solver and response mapping end to end.
 *
 * <p>CSE-007 uses the Spring 2027 snapshot from {@code data/cse007-snapshot} on the data branch:
 * lecture 010 plus exactly one of labs 060 or 062. DEMO-100 is synthetic: a lecture whose only lab
 * is closed. {@code app.catalog.source} is set so no other catalog implementation activates.
 */
@SpringBootTest(properties = "app.catalog.source=engine-integration-test")
@AutoConfigureMockMvc
@Import(ScheduleEngineIntegrationTest.TestCatalog.class)
class ScheduleEngineIntegrationTest {

	private static final String TERM = "2027-SPRING";

	@Autowired MockMvc mvc;

	@TestConfiguration
	static class TestCatalog {

		private static final List<CourseSummary> COURSES = List.of(
				new CourseSummary("CSE-007", "Introduction to Programming", 4),
				new CourseSummary("DEMO-100", "Synthetic Lab Course", 3));

		private static final List<CatalogSection> SECTIONS = List.of(
				section("CSE-007", "12275", "010", 4, "LECTURE", null, Status.OPEN, "09:20", "10:35", DayOfWeek.MONDAY),
				section("CSE-007", "13132", "060", 0, "LAB", "12275", Status.OPEN, "09:20", "10:35",
						DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
				section("CSE-007", "12638", "062", 0, "LAB", "12275", Status.OPEN, "10:45", "12:00",
						DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
				section("DEMO-100", "90001", "010", 3, "LECTURE", null, Status.OPEN, "13:00", "14:15",
						DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
				section("DEMO-100", "90002", "011", 0, "LAB", "90001", Status.CLOSED, "13:00", "15:50", DayOfWeek.FRIDAY));

		private static CatalogSection section(String courseId, String crn, String code, int credits, String component,
				String lectureLink, Status status, String start, String end, DayOfWeek... days) {
			return new CatalogSection(TERM, courseId, crn, code, credits, component, lectureLink, status,
					List.of(new CatalogSection.Meeting(List.of(days), LocalTime.parse(start), LocalTime.parse(end), "TBD")));
		}

		@Bean
		CourseCatalog courseCatalog() {
			return new CourseCatalog() {
				@Override
				public boolean termExists(String term) {
					return TERM.equals(term);
				}

				@Override
				public Optional<CourseSummary> findCourse(String term, String courseId) {
					return termExists(term) ? COURSES.stream().filter(c -> c.courseId().equals(courseId)).findFirst()
							: Optional.empty();
				}

				@Override
				public List<CourseSummary> listCourses(String term) {
					return termExists(term) ? COURSES : List.of();
				}
			};
		}

		@Bean
		SectionCatalog sectionCatalog() {
			return (term, courseIds) -> SECTIONS.stream()
				.filter(s -> s.term().equals(term) && courseIds.contains(s.courseId()))
				.toList();
		}
	}

	private String request(String required, String extra) {
		return """
			{"term":"%s",%s
			 "preferences":{"hardConstraints":{"requiredCourseIds":["%s"],"minCredits":1,"maxCredits":18,
			   "blockedTimes":[]},
			 "softPreferences":{"earliestStart":{"value":"10:00","weight":3}}}}
			""".formatted(TERM, extra, required);
	}

	@Test
	void cse007IsScheduledAsLecturePlusExactlyOneLabRankedByPreference() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(request("CSE-007", "")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules.length()").value(2))
			// Lab 062 starts at 10:45, so only the 09:20 lecture is early: 40 min -> 2 points.
			.andExpect(jsonPath("$.schedules[0].rank").value(1))
			.andExpect(jsonPath("$.schedules[0].sections[*].crn", contains("12275", "12638")))
			.andExpect(jsonPath("$.schedules[0].score").value(98))
			.andExpect(jsonPath("$.schedules[0].scoreBreakdown.earliestStart").value(2))
			.andExpect(jsonPath("$.schedules[0].totalCredits").value(4))
			.andExpect(jsonPath("$.schedules[0].overload").value(false))
			.andExpect(jsonPath("$.schedules[0].sections[0].title").value("Introduction to Programming"))
			// Lab 060 also starts at 09:20 on W/F: 120 early min -> 5 points.
			.andExpect(jsonPath("$.schedules[1].sections[*].crn", contains("12275", "13132")))
			.andExpect(jsonPath("$.schedules[1].score").value(95))
			.andExpect(jsonPath("$.searchLimitReached").value(false));
	}

	@Test
	void lectureWhoseOnlyLabIsClosedCannotBeScheduled() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(request("DEMO-100", "")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules.length()").value(0))
			.andExpect(jsonPath("$.diagnostics", hasItem("DEMO-100: no sections available.")));
	}

	@Test
	void includingClosedSectionsSchedulesTheLectureWithItsLab() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON)
				.content(request("DEMO-100", "\"includeClosedSections\":true,")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules.length()").value(1))
			.andExpect(jsonPath("$.schedules[0].sections[*].crn", contains("90001", "90002")))
			.andExpect(jsonPath("$.schedules[0].totalCredits").value(3));
	}

	@Test
	void blockedTimeOverEveryLectureIsExplained() throws Exception {
		String body = """
			{"term":"%s","preferences":{"hardConstraints":{"requiredCourseIds":["CSE-007"],"minCredits":1,
			 "maxCredits":18,"blockedTimes":[{"days":["MONDAY"],"start":"09:00","end":"11:00"}]}}}
			""".formatted(TERM);
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules.length()").value(0))
			.andExpect(jsonPath("$.diagnostics", hasItem("CSE-007: every section overlaps your blocked times.")));
	}

}
