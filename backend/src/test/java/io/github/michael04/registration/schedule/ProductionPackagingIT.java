package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class ProductionPackagingIT {
	@Test void executableJarContainsOnlyAuthoritativeCatalogsAndNoTestFixtures() throws Exception {
		try (var jar = new ZipFile(Path.of("target/registration-backend-0.0.1-SNAPSHOT.jar").toFile())) {
			String prefix = "BOOT-INF/classes/io/github/michael04/registration/";
			assertThat(jar.getEntry(prefix + "course/DummyCourseCatalog.class")).isNull();
			assertThat(jar.getEntry(prefix + "course/DummySectionCatalog.class")).isNull();
			assertThat(jar.getEntry(prefix + "course/persistence/CatalogPersistenceConfiguration.class")).isNull();
			assertThat(jar.getEntry(prefix + "course/persistence/DatabaseCourseCatalog.class")).isNull();
			assertThat(jar.getEntry(prefix + "course/persistence/CatalogRepository.class")).isNull();
			assertThat(jar.getEntry("BOOT-INF/classes/application-test.properties")).isNull();
			assertThat(jar.getEntry(prefix + "course/persistence/JdbcCourseCatalog.class")).isNotNull();
			assertThat(jar.getEntry(prefix + "course/persistence/JdbcSectionCatalog.class")).isNotNull();
			assertThat(jar.getEntry(prefix + "course/persistence/CatalogDatabaseConfiguration.class")).isNotNull();
			assertThat(jar.getEntry(prefix + "schedule/integration/NativeSchedulingEngine.class")).isNotNull();
			String properties = new String(jar.getInputStream(jar.getEntry("BOOT-INF/classes/application.properties")).readAllBytes(), StandardCharsets.UTF_8);
			assertThat(properties).contains("app.catalog.source=${CATALOG_SOURCE:database}", "scheduling.node-cap=${SCHEDULING_NODE_CAP:200000}");
		}
	}
}
