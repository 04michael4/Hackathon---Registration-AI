package io.github.michael04.registration.engine;

import static io.github.michael04.registration.engine.EngineFixtures.lecture;
import static io.github.michael04.registration.engine.EngineFixtures.linked;
import static io.github.michael04.registration.engine.EngineFixtures.meeting;
import static io.github.michael04.registration.engine.EngineFixtures.required;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CourseOptionTest {

	private static final CourseOption CHM_030 = required("CHM-030",
			lecture("1", "CHM-030", 4, meeting("MWF", "09:00", "09:50")),
			linked("11", "CHM-030", "LAB", "1", meeting("T", "13:00", "15:50")),
			linked("12", "CHM-030", "LAB", "1", meeting("R", "13:00", "15:50")),
			linked("21", "CHM-030", "REC", "1", meeting("F", "14:00", "14:50")),
			lecture("2", "CHM-030", 4, meeting("TR", "09:00", "10:15")),
			linked("13", "CHM-030", "LAB", "2", meeting("W", "13:00", "15:50")));

	private static List<String> crns(CourseOption course) {
		return course.sections().stream().map(SectionOption::crn).toList();
	}

	@Test
	void excludingOneOfSeveralLabsKeepsTheLecture() {
		CourseOption result = CHM_030.excluding(s -> s.crn().equals("11"));

		assertEquals(List.of("1", "12", "21", "2", "13"), crns(result));
	}

	@Test
	void excludingEveryLabOfAComponentRemovesTheLecture() {
		CourseOption result = CHM_030.excluding(s -> Set.of("11", "12").contains(s.crn()));

		// Lecture 1 has no LAB left, so it and its remaining recitation go too.
		assertEquals(List.of("2", "13"), crns(result));
	}

	@Test
	void excludingALectureRemovesItsLinkedSections() {
		CourseOption result = CHM_030.excluding(s -> s.crn().equals("2"));

		assertEquals(List.of("1", "11", "12", "21"), crns(result));
	}

	@Test
	void excludingNothingKeepsEverything() {
		assertEquals(CHM_030, CHM_030.excluding(s -> false));
	}

}
