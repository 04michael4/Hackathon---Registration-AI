package io.github.michael04.registration.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns what the demo knows about an offering, and what the student told it, into warnings. Nothing here decides that a student
 * may register: prerequisites are always "not verified", an unknown profile field is UNRESOLVED, and a missing profile never
 * means eligible. A known restriction the profile fails is OVERRIDE_REQUIRED (an approved override may exist, never guaranteed).
 */
public final class DemoEligibility {

	public static final String UNVERIFIED = "UNVERIFIED";
	public static final String OVERRIDE_REQUIRED = "OVERRIDE_REQUIRED";

	private static final Map<String, String> CLASSES = Map.of("FR", "first-year (FR)", "SO", "sophomore (SO)", "JR", "junior (JR)",
			"SR", "senior (SR)", "GR", "graduate (GR)");
	private static final Map<String, String> COLLEGES = Map.of("EN", "P.C. Rossin College of Engineering (EN)",
			"AS", "College of Arts & Sciences (AS)");

	private DemoEligibility() {
	}

	/** A course's warnings and its overall status: OVERRIDE_REQUIRED if a known restriction is failed, else UNVERIFIED. */
	public record CourseReport(String offeringId, String title, String status, List<DemoWarning> warnings) {
	}

	public static CourseReport report(DemoFixture.Offering o, DemoProfile profile) {
		List<DemoWarning> out = new ArrayList<>();
		String id = o.offeringId();
		out.add(new DemoWarning("REGISTRATION_ELIGIBILITY_NOT_CONFIRMED", "WARNING", "UNVERIFIED", "RESEARCH", id,
				"Registration eligibility not confirmed. This is a proposed timetable option, not a registration."));
		for (DemoFixture.Rule rule : o.rules()) {
			out.add(ruleWarning(id, rule, profile));
		}
		if (o.prerequisiteNote() != null) {
			out.add(new DemoWarning("PREREQUISITE_NOT_VERIFIED", "WARNING", "UNVERIFIED", "CATALOG", id,
					"Prerequisite not verified: " + o.prerequisiteNote()));
		}
		switch (o.components() == null ? "" : o.components()) {
			case "LABS_USER_SUPPLIED" -> out.add(new DemoWarning("REQUIRED_LAB", "INFO", "UNVERIFIED", "USER_SUPPLIED", id,
					"Exactly one lab section is required with the lecture; the lab adds 0 credits (user-supplied confirmation)."));
			case "LABS_UNVERIFIED" -> out.add(new DemoWarning("REQUIRED_LAB_UNVERIFIED", "WARNING", "UNVERIFIED", "BANNER", id,
					"A lab is listed with the lecture; the required lab count is not confirmed."));
			case "STANDALONE_USER_SUPPLIED" -> out.add(new DemoWarning("STANDALONE_USER_SUPPLIED", "INFO", "UNVERIFIED", "USER_SUPPLIED", id,
					"Registers without an additional section (user-supplied confirmation)."));
			case "COMPONENTS_UNVERIFIED" -> out.add(new DemoWarning("LINKED_COMPONENTS_UNVERIFIED", "WARNING", "UNVERIFIED", "BANNER", id,
					"Required linked components are not verified (Banner shows none, which is not proof)."));
			default -> { }
		}
		if (o.arrangedMeeting() != null) {
			out.add(new DemoWarning("ARRANGED_MEETING", "WARNING", "UNRESOLVED", "CATALOG", id,
					"Additional team meeting time must be arranged. " + o.arrangedMeeting()));
		}
		for (DemoFixture.Exclusion e : o.creditExclusions()) {
			out.add(new DemoWarning("CREDIT_EXCLUSION", "WARNING", "UNVERIFIED", "CATALOG", id, "Credit exclusion may apply: " + e.text() + "."));
		}
		if (!"Lecture".equalsIgnoreCase(o.administrativeType()) && !"Lab".equalsIgnoreCase(o.administrativeType())) {
			out.add(new DemoWarning("ADMINISTRATIVE_TYPE", "WARNING", "UNVERIFIED", "BANNER", id,
					"Banner reports this section type as " + o.administrativeType() + "; it is shown as a conditional option."));
		}
		if ("UNVERIFIED".equals(o.crossListing())) {
			out.add(new DemoWarning("CROSS_LISTING_UNVERIFIED", "INFO", "UNVERIFIED", "RESEARCH", id, "Cross-listing is not verified."));
		}
		if (!o.aliases().isEmpty()) {
			out.add(new DemoWarning("CATALOG_ALIAS", "INFO", "UNVERIFIED", "CATALOG", id, "Also listed in the catalog as " + String.join(", ", o.aliases())
					+ " (no Spring 2027 section under that prefix is verified)."));
		}
		for (String note : o.notes()) {
			out.add(new DemoWarning("NOTE", "INFO", "UNVERIFIED", note.startsWith("TEAM_POLICY") ? "TEAM_POLICY" : "RESEARCH", id, note));
		}
		boolean override = out.stream().anyMatch(w -> OVERRIDE_REQUIRED.equals(w.outcome()));
		return new CourseReport(id, o.title(), override ? OVERRIDE_REQUIRED : UNVERIFIED, List.copyOf(out));
	}

	private static DemoWarning ruleWarning(String id, DemoFixture.Rule rule, DemoProfile p) {
		List<String> v = rule.values();
		boolean cannot = "CANNOT".equals(rule.polarity());
		switch (rule.kind()) {
			case "CLASS_STANDING": {
				String names = v.stream().map(c -> CLASSES.getOrDefault(c, c)).collect(Collectors.joining(", "));
				String head = cannot ? "Class standing restriction — Banner excludes " + names : "Class standing restriction — Banner allows only " + names;
				if (p.classStanding() == null) {
					return new DemoWarning("CLASS_STANDING", "WARNING", "UNRESOLVED", "BANNER", id, head + "; your class standing was not provided.");
				}
				boolean inSet = v.contains(p.classStanding());
				boolean fails = cannot ? inSet : !inSet;
				return fails
						? new DemoWarning("CLASS_STANDING", "WARNING", OVERRIDE_REQUIRED, "BANNER", id,
								head + ". Your standing is " + CLASSES.getOrDefault(p.classStanding(), p.classStanding())
										+ ", so an approved CLASS override may be required (not guaranteed).")
						: new DemoWarning("CLASS_STANDING", "INFO", "SATISFIED", "BANNER", id,
								head + ". Your standing (" + p.classStanding() + ") meets it, as far as the demo can tell.");
			}
			case "LEVEL": {
				String names = v.stream().map(c -> "GR".equals(c) ? "graduate" : "UG".equals(c) ? "undergraduate" : c).collect(Collectors.joining(", "));
				String head = "Level restriction — Banner allows only " + names + " students";
				if (p.level() == null) {
					return new DemoWarning("LEVEL", "WARNING", "UNRESOLVED", "BANNER", id, head + "; your level was not provided.");
				}
				return v.contains(p.level())
						? new DemoWarning("LEVEL", "INFO", "SATISFIED", "BANNER", id, head + ". Your level (" + p.level() + ") meets it, as far as the demo can tell.")
						: new DemoWarning("LEVEL", "WARNING", OVERRIDE_REQUIRED, "BANNER", id, head + ". Your level is " + p.level() + "; an approved override may be required (not guaranteed).");
			}
			case "COLLEGE": {
				String names = v.stream().map(c -> COLLEGES.getOrDefault(c, c)).collect(Collectors.joining(" or "));
				String head = "College restriction — Banner requires " + names;
				if (p.college() == null) {
					return new DemoWarning("COLLEGE", "WARNING", "UNRESOLVED", "BANNER", id, head + "; your college was not provided.");
				}
				return v.contains(p.college())
						? new DemoWarning("COLLEGE", "INFO", "SATISFIED", "BANNER", id, head + ". Your college (" + p.college() + ") meets it, as far as the demo can tell.")
						: new DemoWarning("COLLEGE", "WARNING", OVERRIDE_REQUIRED, "BANNER", id, head + ". Your college is " + p.college() + "; an approved override may be required (not guaranteed).");
			}
			case "MAJOR": {
				String head = "Major / field-of-study restriction — Banner lists " + String.join(", ", v);
				if (p.majors().isEmpty()) {
					return new DemoWarning("MAJOR", "WARNING", "UNRESOLVED", "BANNER", id, head + "; your major was not provided.");
				}
				return p.majors().stream().anyMatch(v::contains)
						? new DemoWarning("MAJOR", "INFO", "SATISFIED", "BANNER", id, head + ". Your major meets it, as far as the demo can tell.")
						: new DemoWarning("MAJOR", "WARNING", OVERRIDE_REQUIRED, "BANNER", id, head + ". Your major is not listed; an approved override may be required (not guaranteed).");
			}
			case "DEPARTMENT_APPROVAL":
				return new DemoWarning("DEPARTMENT_APPROVAL", "WARNING", "UNRESOLVED", "BANNER", id, "Department permission is required (Banner); the demo cannot check it.");
			default:
				return new DemoWarning("RESTRICTION_NOT_UNDERSTOOD", "WARNING", "UNRESOLVED", "BANNER", id,
						"Banner lists a restriction the demo could not interpret; treat registration as unverified.");
		}
	}

}
