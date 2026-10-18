package io.github.michael04.registration.demo;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * The demo catalog file: proposed scheduling options for one term, read from {@code DEMO_FIXTURE_PATH}.
 * Written by {@code data/catalog-promotion/tools/build_demo_fixture.py} from collected Banner evidence and the research
 * workbook. Nothing in it is Registrar-certified and nothing in it guarantees registration.
 */
public record DemoFixture(String term, String source, String caution, List<Offering> offerings) {

	public DemoFixture {
		offerings = offerings == null ? List.of() : List.copyOf(offerings);
	}

	/** One offering: a course, or one topic of a special-topic course number (offeringId is then e.g. CSE-398-042). */
	public record Offering(String offeringId, String courseId, String subject, String number, String topic, String title,
			int credits, String administrativeType, String dataSource, String tier, List<Section> sections,
			List<Rule> rules, String prerequisiteNote, String components, String arrangedMeeting,
			List<Exclusion> creditExclusions, List<String> aliases, String crossListing, List<String> notes) {

		public Offering {
			sections = sections == null ? List.of() : List.copyOf(sections);
			rules = rules == null ? List.of() : List.copyOf(rules);
			creditExclusions = creditExclusions == null ? List.of() : List.copyOf(creditExclusions);
			aliases = aliases == null ? List.of() : List.copyOf(aliases);
			notes = notes == null ? List.of() : List.copyOf(notes);
		}
	}

	/** A lecture or a lab. A lab names its lecture; labs carry no credits of their own in this model. */
	public record Section(String crn, String sectionCode, String component, int credits, String lectureCrn,
			List<Meeting> meetings) {

		public Section {
			meetings = meetings == null ? List.of() : List.copyOf(meetings);
		}
	}

	public record Meeting(List<DayOfWeek> days, LocalTime start, LocalTime end, String location) {
	}

	/** A registration restriction known from Banner: CLASS_STANDING, LEVEL, COLLEGE, MAJOR, DEPARTMENT_APPROVAL or UNPARSED. */
	public record Rule(String kind, String polarity, List<String> values, String source) {

		public Rule {
			values = values == null ? List.of() : List.copyOf(values);
		}
	}

	public record Exclusion(String with, String kind, String text) {
	}

}
