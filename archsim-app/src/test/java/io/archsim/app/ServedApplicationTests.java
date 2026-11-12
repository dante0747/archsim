package io.archsim.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application behind a real Tomcat: where it listens, and the answers that come from
 * the servlet container rather than Spring MVC, which carry the API's bodies too.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ServedApplicationTests {

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private WebServerApplicationContext context;

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

	@Test
	void get_encodedPercentInThePath_isRefusedByTheFirewallWithTheApisBody() throws IOException, InterruptedException {
		HttpResponse<String> response = send("GET", "/api/v1/meta%25");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).isEqualTo("{\"detail\":\"Bad Request\"}");
	}

	@Test
	void propfind_methodTheFirewallRefuses_isABadRequestWithTheApisBody() throws IOException, InterruptedException {
		HttpResponse<String> response = send("PROPFIND", "/api/v1/meta");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).isEqualTo("{\"detail\":\"Bad Request\"}");
	}

	@Test
	void start_theApi_hasNoUsersOfSpringSecuritysOwn() {
		assertThat(this.context.getBeanProvider(UserDetailsService.class).getIfAvailable()).isNull();
	}

	@Test
	void listen_withoutAServerAddress_acceptsLoopbackConnectionsOnly() {
		TomcatWebServer server = (TomcatWebServer) Objects.requireNonNull(this.context.getWebServer());
		Connector connector = server.getTomcat().getConnector();

		InetAddress address = ((AbstractProtocol<?>) connector.getProtocolHandler()).getAddress();

		assertThat(address).extracting(InetAddress::getHostAddress).isEqualTo("127.0.0.1");
	}

	private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path))
			.method(method, HttpRequest.BodyPublishers.noBody())
			.build();
		return this.http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
