package io.archsim.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application behind a real Tomcat: the answers that come from the servlet container
 * rather than Spring MVC carry the API's bodies too.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ServedApplicationTests {

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		String database = TestDatabases.freshJdbcUrl();
		registry.add("archsim.database-url", () -> database);
	}

	@Test
	void errorPage_askedForDirectly_isARouteThatDoesNotExist() throws IOException, InterruptedException {
		HttpResponse<String> response = send("GET", "/error");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).isEqualTo("{\"detail\":\"Not Found\"}");
	}

	@Test
	void trace_refusedByTheContainer_isMethodNotAllowedWithTheApisBody() throws IOException, InterruptedException {
		HttpResponse<String> response = send("TRACE", "/api/v1/meta");

		assertThat(response.statusCode()).isEqualTo(405);
		assertThat(response.body()).isEqualTo("{\"detail\":\"Method Not Allowed\"}");
	}

	@Test
	void get_encodedSlashInThePath_isARouteThatDoesNotExist() throws IOException, InterruptedException {
		HttpResponse<String> response = send("GET", "/api/v1/me%2Fta");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).isEqualTo("{\"detail\":\"Not Found\"}");
	}

	private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path))
			.method(method, HttpRequest.BodyPublishers.noBody())
			.build();
		return this.http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
