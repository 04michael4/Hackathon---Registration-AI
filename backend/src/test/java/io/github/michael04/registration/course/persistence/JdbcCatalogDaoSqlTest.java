package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Static guards on the SQL text: only the catalog_api views, read-only, parameterized, certified courses only. */
class JdbcCatalogDaoSqlTest {

	private static final List<String> ALL = List.of(JdbcCatalogDao.TERM_EXISTS, JdbcCatalogDao.COURSES, JdbcCatalogDao.COURSE,
			JdbcCatalogDao.SECTIONS, JdbcCatalogDao.MEETINGS);

	@Test
	void everyQueryReadsOnlyTheCatalogApiViews() {
		for (String sql : ALL) {
			assertThat(sql).startsWith("SELECT").contains("catalog_api.").doesNotContain("cse007_snapshot");
		}
	}

	@Test
	void noQueryCanWrite() {
		for (String sql : ALL) {
			assertThat(sql.toUpperCase()).doesNotContain("INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "TRUNCATE ", "CREATE ", ";");
		}
	}

	@Test
	void everyQueryIsScopedToABannerTermAndParameterized() {
		for (String sql : ALL) {
			assertThat(sql).contains("term_code = ?");
			// No values are spliced into the text. The only literal allowed is the constant "," separator.
			assertThat(sql.replace("','", "")).doesNotContain("'");
		}
	}

	@Test
	void courseQueriesReturnOnlySchedulableCourses() {
		assertThat(JdbcCatalogDao.COURSES).contains("AND schedulable");
		assertThat(JdbcCatalogDao.COURSE).contains("AND schedulable");
	}

	@Test
	void sectionsAndMeetingsComeOnlyFromTheCertifiedViews() {
		assertThat(JdbcCatalogDao.SECTIONS).contains("catalog_api.section_offering");
		assertThat(JdbcCatalogDao.MEETINGS).contains("catalog_api.section_meeting");
		assertThat(JdbcCatalogDao.SECTIONS + JdbcCatalogDao.MEETINGS).doesNotContain("course_offering", "section_link_resolved");
	}

}
