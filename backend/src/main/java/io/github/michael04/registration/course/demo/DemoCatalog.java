package io.github.michael04.registration.course.demo;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CatalogSection.Status;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseMetadataCatalog;
import io.github.michael04.registration.course.CourseResponses;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.course.SectionLookupCatalog;

/**
 * Opt-in demo catalog ({@code CATALOG_SOURCE=demo}) so the frontend can be demonstrated without PostgreSQL.
 * Every course, section, time, instructor and description here is <b>made up</b>; it is not Lehigh data.
 *
 * <p>The default stays {@code database} (the authoritative catalog). Nothing falls back to this class: it exists
 * only when explicitly selected. It covers what the demo needs: lectures with labs and recitations, a closed
 * section, a course offered only MWF 8:00am, early, late and Friday-only sections, and clashing sections.
 */
@Component
@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "demo")
public class DemoCatalog implements CourseCatalog, SectionCatalog, CourseMetadataCatalog, SectionLookupCatalog {

	public static final String TERM = "2027-SPRING";

	private static final DayOfWeek M = DayOfWeek.MONDAY;
	private static final DayOfWeek T = DayOfWeek.TUESDAY;
	private static final DayOfWeek W = DayOfWeek.WEDNESDAY;
	private static final DayOfWeek R = DayOfWeek.THURSDAY;
	private static final DayOfWeek F = DayOfWeek.FRIDAY;

	private final Map<String, CourseSummary> courses = new TreeMap<>();
	private final Map<String, String> descriptions = new TreeMap<>();
	private final Map<String, CatalogSection> sections = new TreeMap<>();
	private final Map<String, List<String>> instructors = new TreeMap<>();

	public DemoCatalog() {
		course("CSE-017", "Programming and Data Structures", 3,
				"Object-oriented programming in Java, recursion, and core data structures: lists, stacks, trees, hash tables.")
			.lecture("41001", "010", "MWF", "09:00", "09:50", "Prof. Ana Moreno")
			.lecture("41002", "011", "TR", "13:00", "14:15", "Prof. Ana Moreno")
			.lecture("41003", "012", Status.CLOSED, "MWF", "14:00", "14:50", "Dr. Kwame Osei");
		course("CSE-109", "Systems Software", 3,
				"C programming, memory, processes and the tools behind compiled software. Offered in one section only.")
			.lecture("42001", "010", "MWF", "08:00", "08:50", "Dr. Lena Fischer");
		course("CSE-140", "Foundations of Discrete Structures and Algorithms", 3,
				"Logic, proofs, sets, graphs and an introduction to algorithm analysis.")
			.lecture("43001", "010", "MWF", "11:00", "11:50", "Dr. Priya Raman")
			.lecture("43002", "011", "TR", "10:45", "12:00", "Dr. Priya Raman");
		course("CSE-202", "Computer Organization and Architecture", 3,
				"How a processor runs a program: instruction sets, memory hierarchy and pipelining.")
			.lecture("44001", "010", "TR", "09:20", "10:35", "Dr. Marco Bianchi");
		course("CSE-216", "Software Engineering", 3,
				"Team software projects: requirements, design, testing and version control.")
			.lecture("45001", "010", "MW", "15:00", "16:15", "Prof. Hannah Cole")
			.lecture("45002", "011", "TR", "15:00", "16:15", "Prof. Hannah Cole");
		course("CSE-241", "Database Systems and Applications", 3,
				"Relational design, SQL and building applications on a database.")
			.lecture("46001", "010", "MW", "13:30", "14:45", "Dr. Samuel Ortiz");
		course("CSE-262", "Programming Languages", 3,
				"Functional, logic and object-oriented paradigms, and how languages are defined and implemented.")
			.lecture("46501", "010", "TR", "13:30", "14:45", "Dr. Yuki Tanaka");
		course("MATH-021", "Calculus I", 4,
				"Limits, derivatives and integrals of one variable, with applications.")
			.lecture("47001", "010", "MWF", "10:00", "10:50", "Dr. Elena Petrova")
			.lecture("47002", "011", "TR", "10:30", "11:45", "Dr. Elena Petrova")
			.lecture("47003", "012", "MWF", "13:00", "13:50", "Dr. James Whitfield");
		course("MATH-022", "Calculus II", 4,
				"Techniques of integration, sequences and series, and parametric curves.")
			.lecture("48001", "010", "MWF", "09:00", "09:50", "Dr. James Whitfield")
			.lecture("48002", "011", "TR", "13:30", "14:45", "Dr. Elena Petrova");
		course("MATH-205", "Linear Methods", 3,
				"Matrices, vector spaces, eigenvalues and linear differential equations.")
			.lecture("48501", "010", "MWF", "12:00", "12:50", "Dr. Omar Haddad");
		course("MATH-231", "Probability and Statistics", 3,
				"Probability models, random variables, estimation and hypothesis tests.")
			.lecture("49001", "010", "TR", "09:20", "10:35", "Dr. Grace Liu")
			.lecture("49002", "011", "MW", "16:30", "17:45", "Dr. Grace Liu");
		course("PHY-011", "Introductory Physics I", 4,
				"Mechanics: motion, forces, energy and momentum. Includes a weekly lab and recitation.")
			.lecture("50001", "010", "MWF", "10:00", "10:50", "Prof. Daniel Brooks")
			.linked("50011", "011", "LAB", "50001", "T", "13:00", "15:50", "Lab staff")
			.linked("50012", "012", "LAB", "50001", "R", "13:00", "15:50", "Lab staff")
			.linked("50021", "013", "RECITATION", "50001", "M", "15:00", "15:50", "Teaching assistant")
			.linked("50022", "014", "RECITATION", "50001", "W", "15:00", "15:50", "Teaching assistant")
			.lecture("50002", "020", "TR", "08:00", "09:15", "Prof. Daniel Brooks")
			.linked("50013", "021", "LAB", "50002", "W", "13:00", "15:50", "Lab staff")
			.linked("50023", "022", "RECITATION", "50002", "F", "11:00", "11:50", "Teaching assistant");
		course("CHM-030", "Introduction to Chemical Principles", 4,
				"Atomic structure, bonding, reactions and equilibrium, with a weekly lab.")
			.lecture("51001", "010", "MWF", "11:00", "11:50", "Dr. Rosa Alvarez")
			.linked("51011", "011", "LAB", "51001", "T", "08:00", "10:50", "Lab staff")
			.linked("51012", "012", "LAB", "51001", "R", "15:00", "17:50", "Lab staff");
		course("ECO-001", "Principles of Economics", 4,
				"Supply and demand, markets, and the basics of the national economy.")
			.lecture("52001", "010", "MW", "10:00", "10:50", "Prof. Victor Nash")
			.linked("52011", "011", "RECITATION", "52001", "R", "09:00", "09:50", "Teaching assistant")
			.linked("52012", "012", "RECITATION", "52001", "F", "10:00", "10:50", "Teaching assistant")
			.lecture("52002", "020", "TR", "14:30", "15:45", "Prof. Victor Nash")
			.linked("52021", "021", "RECITATION", "52002", "F", "13:00", "13:50", "Teaching assistant");
		course("ENGL-002", "Research and Argument", 3,
				"Writing researched arguments: finding sources, structuring a case and revising.")
			.lecture("53001", "010", "MWF", "08:00", "08:50", "Dr. Claire Dubois")
			.lecture("53002", "011", "TR", "16:00", "17:15", "Dr. Claire Dubois")
			.lecture("53003", "012", "F", "09:00", "11:50", "Prof. Ian Reyes");
		course("PHIL-105", "Ethics", 3,
				"Major ethical theories applied to current questions in technology and society.")
			.lecture("54001", "010", "TR", "11:00", "12:15", "Prof. Ian Reyes");
		course("PSY-001", "Introduction to Psychology", 3,
				"Perception, memory, learning, development and social behaviour.")
			.lecture("55001", "010", "MWF", "14:00", "14:50", "Dr. Nora Lindqvist")
			.lecture("55002", "011", "TR", "18:10", "19:25", "Dr. Nora Lindqvist");
		course("ART-011", "Drawing I", 3,
				"Observational drawing in studio, from line and value to composition.")
			.lecture("56001", "010", "TR", "15:00", "17:50", "Prof. Mateo Silva");
	}

	@Override
	public boolean termExists(String term) {
		return TERM.equals(term);
	}

	@Override
	public Optional<CourseSummary> findCourse(String term, String courseId) {
		return termExists(term) ? Optional.ofNullable(courses.get(courseId)) : Optional.empty();
	}

	@Override
	public List<CourseSummary> listCourses(String term) {
		return termExists(term) ? List.copyOf(courses.values()) : List.of();
	}

	@Override
	public List<CatalogSection> sectionsFor(String term, List<String> courseIds) {
		return sections.values().stream()
			.filter(s -> s.term().equals(term) && courseIds.contains(s.courseId()))
			.toList();
	}

	@Override
	public List<CatalogSection> sectionsByCrn(String term, List<String> crns) {
		return sections.values().stream()
			.filter(s -> s.term().equals(term) && crns.contains(s.crn()))
			.sorted(Comparator.comparing(CatalogSection::crn))
			.toList();
	}

	@Override
	public List<CourseResponses.TermResponse> terms() {
		return List.of(new CourseResponses.TermResponse(TERM, "Spring 2027 (demo data)", LocalDate.of(2026, 10, 16)));
	}

	@Override
	public Detail detail(String term, String courseId) {
		Map<String, List<String>> byCrn = new LinkedHashMap<>();
		sections.values().stream()
			.filter(s -> s.term().equals(term) && s.courseId().equals(courseId))
			.forEach(s -> byCrn.put(s.crn(), instructors.getOrDefault(s.crn(), List.of())));
		return new Detail(descriptions.get(courseId), byCrn);
	}

	private CourseBuilder course(String id, String title, int credits, String description) {
		courses.put(id, new CourseSummary(id, title, credits));
		descriptions.put(id, description);
		return new CourseBuilder(id, credits);
	}

	/** Keeps the data table above readable. */
	private final class CourseBuilder {

		private final String courseId;
		private final int credits;

		CourseBuilder(String courseId, int credits) {
			this.courseId = courseId;
			this.credits = credits;
		}

		CourseBuilder lecture(String crn, String code, String days, String start, String end, String instructor) {
			return lecture(crn, code, Status.OPEN, days, start, end, instructor);
		}

		CourseBuilder lecture(String crn, String code, Status status, String days, String start, String end,
				String instructor) {
			add(crn, code, credits, "LECTURE", null, status, days, start, end, instructor);
			return this;
		}

		CourseBuilder linked(String crn, String code, String component, String lectureCrn, String days, String start,
				String end, String instructor) {
			add(crn, code, 0, component, lectureCrn, Status.OPEN, days, start, end, instructor);
			return this;
		}

		private void add(String crn, String code, int sectionCredits, String component, String lectureCrn,
				Status status, String days, String start, String end, String instructor) {
			List<DayOfWeek> parsed = new ArrayList<>();
			for (char c : days.toCharArray()) {
				parsed.add(switch (c) {
					case 'M' -> M;
					case 'T' -> T;
					case 'W' -> W;
					case 'R' -> R;
					case 'F' -> F;
					default -> throw new IllegalArgumentException("day letter " + c);
				});
			}
			sections.put(crn, new CatalogSection(TERM, courseId, crn, code, sectionCredits, component, lectureCrn, status,
					List.of(new CatalogSection.Meeting(List.copyOf(parsed), LocalTime.parse(start), LocalTime.parse(end),
							"Demo Hall " + (100 + Math.floorMod(crn.hashCode(), 30))))));
			instructors.put(crn, List.of(instructor));
		}
	}

}
