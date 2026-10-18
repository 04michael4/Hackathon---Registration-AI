package io.github.michael04.registration.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** Without CATALOG_SOURCE=demo there is no demo catalog, no demo endpoint and no demo page. */
@SpringBootTest(properties = { "app.catalog.source=dummy", "app.ai.provider=none" })
@AutoConfigureMockMvc
class DemoModeIsOffByDefaultTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ApplicationContext context;

	@Test
	void noDemoBeansExistAndTheDemoUrlsAreNotFound() throws Exception {
		assertThat(context.getBeansOfType(DemoCatalog.class)).isEmpty();
		assertThat(context.getBeansOfType(DemoController.class)).isEmpty();
		mvc.perform(get("/api/demo/offerings")).andExpect(status().isNotFound());
		mvc.perform(get("/demo")).andExpect(status().isNotFound());
	}

	@Test
	void theDemoSettingDefaultsToNoFixtureSoDemoModeCannotStartByAccident() {
		assertThat(context.getEnvironment().getProperty("app.demo.fixture-path")).isNullOrEmpty();
		assertThat(context.getEnvironment().getProperty("app.catalog.source")).isNotEqualTo("demo");
	}

}
