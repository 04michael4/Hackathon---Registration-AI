package io.github.michael04.registration.course;

import java.util.List;

/** Exact CRN lookups for grounded schedule explanations, scoped to one term. */
public interface SectionLookupCatalog {
	List<CatalogSection> sectionsByCrn(String term, List<String> crns);
}
