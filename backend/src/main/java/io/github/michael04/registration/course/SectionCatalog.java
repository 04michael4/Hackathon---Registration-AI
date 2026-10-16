package io.github.michael04.registration.course;

import java.util.List;

/** Term-scoped section lookup to be implemented by the catalog adapter. */
public interface SectionCatalog {
	List<CatalogSection> sectionsFor(String term, List<String> courseIds);
}
