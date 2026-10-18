package io.github.michael04.registration.course;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.common.json.JsonConfiguration;

/** Predicted ratings appear on course search and detail, instructors fill the sections, and a course without a prediction shows null. */
@WebMvcTest(CourseController.class)
@Import({CourseService.class, DummyCourseCatalog.class, DummySectionCatalog.class, JsonConfiguration.class, CourseRatingsControllerTest.Ratings.class})
class CourseRatingsControllerTest {

	private static final PredictedRating CSE_017 = new PredictedRating(new BigDecimal("3.66"), new BigDecimal("2.95"), "MEDIUM", "PREDICTED", 1, 1);

	@TestConfiguration
	static class Ratings {

		@Bean
		RatingCatalog ratingCatalog() {
			return new RatingCatalog() {
				@Override
				public Map<String, PredictedRating> courseRatings(String term, List<String> courseIds) {
					return courseIds.contains("CSE-017") ? Map.of("CSE-017", CSE_017) : Map.of();
				}

				@Override
				public Map<String, List<InstructorRating>> instructorsByCrn(String term, String courseId) {
					return "CSE-017".equals(courseId) ? Map.of("41001", List.of(
							new InstructorRating("Testwick, Alder", new BigDecimal("3.66"), new BigDecimal("2.95"), "MEDIUM", 12, true),
							new InstructorRating("Quillon, Bree", null, null, null, null, false))) : Map.of();
				}
			};
		}

	}

	@Autowired
	MockMvc mvc;

	@Test
	void searchShowsThePredictedRatingOrNull() throws Exception {
		mvc.perform(get("/api/courses").param("term", "2027-SPRING")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.courseId=='CSE-017')].predictedRating.quality").value(contains(3.66)))
			.andExpect(jsonPath("$.items[?(@.courseId=='CSE-017')].predictedRating.basis").value(contains("PREDICTED")))
			.andExpect(jsonPath("$.items[?(@.courseId=='MATH-021')].predictedRating").value(contains(nullValue())));
	}

	@Test
	void detailShowsTheCourseRatingAndEachInstructorWithoutInventingNumbers() throws Exception {
		mvc.perform(get("/api/courses/CSE-017").param("term", "2027-SPRING")).andExpect(status().isOk())
			.andExpect(jsonPath("$.predictedRating.difficulty").value(2.95))
			.andExpect(jsonPath("$.predictedRating.confidence").value("MEDIUM"))
			.andExpect(jsonPath("$.sections[?(@.crn=='41001')].instructors[*]").value(contains("Testwick, Alder", "Quillon, Bree")))
			.andExpect(jsonPath("$.sections[?(@.crn=='41001')].instructorRatings[*].courseSpecific").value(contains(true, false)))
			.andExpect(jsonPath("$.sections[?(@.crn=='41001')].instructorRatings[*].predictedQuality").value(contains(equalTo(3.66), nullValue())));
	}

	@Test
	void aCourseWithoutRatingsHasNullAndEmptyLists() throws Exception {
		mvc.perform(get("/api/courses/MATH-021").param("term", "2027-SPRING")).andExpect(status().isOk())
			.andExpect(jsonPath("$.predictedRating").value(nullValue()))
			.andExpect(jsonPath("$.sections[0].instructorRatings").isEmpty());
	}

}
