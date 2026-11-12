package io.archsim.app;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
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

	private static final String VALIDATE_BODY_SCHEMA = "$.paths['/api/v1/validate'].post.requestBody"
			+ ".content['application/json'].schema";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private ApplicationContext context;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		String database = TestDatabases.freshJdbcUrl();
		registry.add("archsim.database-url", () -> database);
	}

	@Test
	void start_always_makesNoUsersOfSpringSecuritysOwn() {
		assertThat(this.context.getBeanProvider(UserDetailsService.class).getIfAvailable()).isNull();
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

	@Test
	void openApi_apiBody_isTheRequestBodyWithTheCodecsSchema() {
		assertThat(this.mvc.get().uri("/api/v1/openapi.json")).hasStatusOk()
			.bodyJson()
			.extractingPath(VALIDATE_BODY_SCHEMA + ".required")
			.isEqualTo(List.of("document"));
	}

	@Test
	void openApi_apiBody_saysNothingOfUnknownKeys() {
		assertThat(this.mvc.get().uri("/api/v1/openapi.json")).hasStatusOk()
			.bodyJson()
			.doesNotHavePath(VALIDATE_BODY_SCHEMA + ".additionalProperties");
	}

	@Test
	void openApi_apiBody_isNoQueryParameter() {
		assertThat(this.mvc.get().uri("/api/v1/openapi.json")).hasStatusOk()
			.bodyJson()
			.doesNotHavePath("$.paths['/api/v1/validate'].post.parameters");
	}

	@Test
	void openApi_listOfProjects_namesTheWorkspaceParameter() {
		assertThat(this.mvc.get().uri("/api/v1/openapi.json")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.paths['/api/v1/projects'].get.parameters[0].name")
			.isEqualTo("workspace");
	}

}
