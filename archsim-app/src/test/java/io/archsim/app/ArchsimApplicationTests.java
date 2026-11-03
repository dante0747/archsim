package io.archsim.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application on a real PostgreSQL: it migrates an empty database and serves
 * the endpoints the editor needs first.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArchsimApplicationTests {

	@Autowired
	private MockMvcTester mvc;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		String database = TestDatabases.freshJdbcUrl();
		registry.add("archsim.database-url", () -> database);
	}

	@Test
	void health_migratedDatabase_isOk() {
		assertThat(this.mvc.get().uri("/api/v1/health")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("{\"status\": \"ok\", \"database\": \"ok\"}");
	}

	@Test
	void meta_always_namesTheDatabaseAndTheSchemaVersion() {
		assertThat(this.mvc.get().uri("/api/v1/meta")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("{\"schemaVersion\": \"1.7\", \"database\": \"postgresql\"}");
	}

	@Test
	void components_always_listTheStandardTypes() {
		assertThat(this.mvc.get().uri("/api/v1/components")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.components[0].type")
			.isEqualTo("traffic_source");
	}

	@Test
	void examples_repositoryExamples_includeTheStarter() {
		assertThat(this.mvc.get().uri("/api/v1/examples/cache-aside-starter")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.schemaVersion")
			.isEqualTo("1.3");
	}

	@Test
	void openApi_always_describesTheApi() {
		assertThat(this.mvc.get().uri("/api/v1/openapi.json")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.paths['/api/v1/health']")
			.isNotNull();
	}

}
