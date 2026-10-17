package io.github.michael04.registration.course;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.common.json.JsonConfiguration;

@WebMvcTest(CourseController.class)
@Import({CourseService.class, JsonConfiguration.class})
class CourseControllerTest {
	@Autowired MockMvc mvc;
	@MockitoBean CourseReadCatalog catalog;

	@Test
	void termsUseTheDocumentedEnvelopeAndDate() throws Exception {
		when(catalog.terms()).thenReturn(List.of(new CourseResponses.TermResponse("202710", "Spring 2027", LocalDate.of(2026, 10, 1))));
		mvc.perform(get("/api/terms")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].term").value("202710"))
			.andExpect(jsonPath("$.items[0].catalogSnapshotDate").value("2026-10-01"));
	}

	@Test
	void searchPreservesFieldsAndPassesPaginationAndFiltersToTheCatalog() throws Exception {
		when(catalog.termExists("202710")).thenReturn(true);
		when(catalog.search("202710", "Data structures", "CSE", 1, 2)).thenReturn(new CourseResponses.PageResponse(
			List.of(new CourseResponses.SummaryResponse("CSE-017", "CSE", "017", "Data structures", new BigDecimal("3.5"), 4)), 1, 2, 3, 2));
		mvc.perform(get("/api/courses").param("term", "202710").param("q", "Data structures")
			.param("subject", "CSE").param("page", "1").param("size", "2"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].number").value("017"))
			.andExpect(jsonPath("$.items[0].credits").value(3.5)).andExpect(jsonPath("$.items[0].sectionCount").value(4))
			.andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.totalItems").value(3));
		verify(catalog).search("202710", "Data structures", "CSE", 1, 2);
	}

	@Test
	void omittedSearchOptionsUseDefaultsAndEmptyMatchesRemain200() throws Exception {
		when(catalog.termExists("202710")).thenReturn(true);
		when(catalog.search("202710", null, null, 0, 20)).thenReturn(new CourseResponses.PageResponse(List.of(), 0, 20, 0, 0));
		mvc.perform(get("/api/courses").param("term", "202710")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalPages").value(0));
	}

	@Test
	void detailPreservesInstructorAndGroupsDaysWithContractTimes() throws Exception {
		when(catalog.termExists("202710")).thenReturn(true);
		when(catalog.detail("202710", "CSE-007")).thenReturn(Optional.of(new CourseResponses.DetailResponse(
			"CSE-007", "CSE", "007", "Programming", new BigDecimal("4.0"), "Java and OOP",
			List.of(new CourseResponses.SectionResponse("12275", "010", List.of("Chen, Brian"), CatalogSection.Status.UNKNOWN,
				List.of(new CourseResponses.MeetingResponse(List.of(DayOfWeek.MONDAY), LocalTime.of(9, 20), LocalTime.of(10, 35), null)))))));
		mvc.perform(get("/api/courses/CSE-007").param("term", "202710")).andExpect(status().isOk())
			.andExpect(jsonPath("$.description").value("Java and OOP"))
			.andExpect(jsonPath("$.sections[0].instructors[0]").value("Chen, Brian"))
			.andExpect(jsonPath("$.sections[0].status").value("UNKNOWN"))
			.andExpect(jsonPath("$.sections[0].meetings[0].start").value("09:20"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/courses", "/api/courses?term=", "/api/courses?term=202710&page=-1",
		"/api/courses?term=202710&size=0", "/api/courses?term=202710&size=101", "/api/courses?term=202710&page=1.5",
		"/api/courses?term=202710&size=nope", "/api/courses?term=202710&subject=CSE1", "/api/courses?term=202710&subject=",
		"/api/courses/CSE-007", "/api/courses/not-a-course?term=202710"})
	void invalidParametersNeverReachCatalog(String url) throws Exception {
		mvc.perform(get(url)).andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(header().exists("X-Request-Id"));
		verifyNoInteractions(catalog);
	}

	@Test
	void oversizedSearchTextNeverReachesCatalog() throws Exception {
		mvc.perform(get("/api/courses").param("term", "202710").param("q", "a".repeat(101)))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(catalog);
	}

	@Test
	void unknownTermIs404ForCourseSearch() throws Exception {
		mvc.perform(get("/api/courses").param("term", "unknown")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Term is not available"));
	}

	@Test
	void unknownCourseIs404ForAKnownTerm() throws Exception {
		when(catalog.termExists("202710")).thenReturn(true);
		when(catalog.detail("202710", "CSE-999")).thenReturn(Optional.empty());
		mvc.perform(get("/api/courses/CSE-999").param("term", "202710")).andExpect(status().isNotFound());
	}

	@Test
	void databaseFailuresAreSanitized() throws Exception {
		when(catalog.terms()).thenThrow(new DataAccessResourceFailureException("SQL and database-password-secret"));
		mvc.perform(get("/api/terms")).andExpect(status().isInternalServerError())
			.andExpect(content().string(not(containsString("database-password-secret"))))
			.andExpect(content().string(not(containsString("DataAccessResourceFailureException"))))
			.andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void unsupportedMethodsUseShared405() throws Exception {
		mvc.perform(post("/api/courses")).andExpect(status().isMethodNotAllowed());
	}
}
