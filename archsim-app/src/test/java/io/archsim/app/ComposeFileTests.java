package io.archsim.app;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import io.archsim.apitests.Examples;
import io.archsim.app.config.TestProperties;
import io.archsim.engine.version.ArchsimVersion;
import io.archsim.engine.yaml.YamlDocuments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The compose files (ADR-0077): {@code docker-compose.yml} runs the release's images, at
 * ArchSim's version, and builds nothing, so Docker alone runs it;
 * {@code docker-compose.local.yml} runs images built from the checkout instead, under
 * names of their own. The hosted kit's {@code deploy/compose.yaml} (ADR-0080) runs the
 * release's images too, behind one way in, Caddy or a Cloudflare Tunnel, and keeps the
 * API and the database where SECURITY.md's trust boundaries put them.
 */
class ComposeFileTests {

	private static final Path ROOT = Examples.repoRoot();

	private static final String RELEASE = "docker-compose.yml";

	private static final String LOCAL = "docker-compose.local.yml";

	private static final String HOSTED = "deploy/compose.yaml";

	private static final String REGISTRY = "ghcr.io/dante0747/";

	private static final String LOCAL_APP_IMAGE = "archsim-app:local";

	/** The services that run archsim-app, each in its role. */
	private static final List<String> APP_SERVICES = List.of("migrate", "api", "worker");

	/** A size at the end of a setting, before the brace that may close its default. */
	private static final Pattern SIZE = Pattern.compile("(\\d+)([mg])}?$");

	/** A JVM's heap in its options: {@code -Xmx}, then a size or a setting's default. */
	private static final Pattern HEAP = Pattern.compile("-Xmx(?:\\$\\{\\w+:-)?(\\d+)([mg])");

	/** What a JVM's container holds besides its heap, by the kit's rule. */
	private static final long BEYOND_HEAP_MB = 384;

	@ParameterizedTest
	@ValueSource(strings = { RELEASE, HOSTED })
	void compose_theAppsServices_runTheReleasedAppImageAtArchsimsVersion(String file) throws IOException {
		JsonNode services = services(file);

		assertThat(APP_SERVICES.stream().map((name) -> services.path(name).path("image").asString()))
			.containsOnly(REGISTRY + "archsim-app:" + ArchsimVersion.PRODUCT);
	}

	@ParameterizedTest
	@ValueSource(strings = { RELEASE, HOSTED })
	void compose_theWebService_runsTheReleasedWebImageAtArchsimsVersion(String file) throws IOException {
		JsonNode web = services(file).path("web");

		assertThat(web.path("image").asString()).isEqualTo(REGISTRY + "archsim-web:" + ArchsimVersion.PRODUCT);
	}

	@ParameterizedTest
	@ValueSource(strings = { RELEASE, HOSTED })
	void compose_everyService_isPulledNotBuilt(String file) throws IOException {
		JsonNode services = services(file);

		assertThat(services.properties()
			.stream()
			.filter((service) -> builtOrNeverPulled(service.getValue()))
			.map(Map.Entry::getKey)).isEmpty();
	}

	// the API, the workers and the database stay off the internet (SECURITY.md); the
	// tunnel connects out and publishes nothing
	@Test
	void hostedCompose_onlyTheReverseProxy_publishesPorts() throws IOException {
		JsonNode services = services(HOSTED);

		assertThat(services.properties()
			.stream()
			.filter((service) -> service.getValue().has("ports"))
			.map(Map.Entry::getKey)).containsExactly("caddy");
	}

	// each way in has a profile of its own, so hosted.env chooses one
	@Test
	void hostedCompose_eachWayIn_startsWithAProfileOfItsOwn() throws IOException {
		JsonNode services = services(HOSTED);

		assertThat(List.of("caddy", "cloudflared")).extracting((name) -> strings(services.path(name).path("profiles")))
			.containsExactly(List.of("caddy"), List.of("tunnel"));
	}

	@Test
	void hostedCompose_theEdgeNetwork_holdsOnlyTheWaysInAndTheWebServer() throws IOException {
		JsonNode services = services(HOSTED);

		assertThat(services.properties()
			.stream()
			.filter((service) -> networks(service.getValue()).contains("edge"))
			.map(Map.Entry::getKey)).containsExactlyInAnyOrder("caddy", "cloudflared", "web");
	}

	// whoever can write to the database can queue, cancel or change runs (SECURITY.md)
	@Test
	void hostedCompose_theDatabase_isOnlyOnTheDataNetwork() throws IOException {
		JsonNode postgres = services(HOSTED).path("postgres");

		assertThat(networks(postgres)).containsExactly("data");
	}

	@Test
	void hostedCompose_theDataNetwork_hasNoWayOut() throws IOException {
		JsonNode networks = document(HOSTED).path("networks");

		assertThat(networks.at("/data/internal").asBoolean()).isTrue();
	}

	// the workers read the mode too: with sign-in, each workspace kind has its limits
	@Test
	void hostedCompose_theApiAndTheWorkers_runWithSignIn() throws IOException {
		JsonNode services = services(HOSTED);

		assertThat(List.of("api", "worker"))
			.extracting((name) -> services.path(name).at("/environment/ARCHSIM_AUTH_MODE").asString())
			.containsOnly("oauth");
	}

	// anyone else's X-ArchSim-Client-IP would let them pick the address rate limits count
	@Test
	void hostedCompose_theApi_believesOnlyTheWebServerAboutVisitorsAddresses() throws IOException {
		JsonNode api = services(HOSTED).path("api");

		assertThat(api.at("/environment/ARCHSIM_TRUSTED_PROXIES").asString()).isEqualTo("web");
	}

	// without sign-in the API answers only this machine's names and the ones it lists
	// (ADR-0107), so it lists the name the web container calls it by
	@Test
	void releaseCompose_theApi_answersTheNameTheWebServerCallsItBy() throws IOException {
		JsonNode services = services(RELEASE);
		URI api = URI.create(services.path("web").at("/environment/ARCHSIM_API_URL").asString());

		assertThat(services.path("api").at("/environment/ARCHSIM_ALLOWED_HOSTS").asString()).isEqualTo(api.getHost());
	}

	// the web app links to the operator's privacy notice when hosted.env names one, and
	// shows no link when it is left out
	@Test
	void hostedCompose_theWebServer_takesThePrivacyNoticeFromHostedEnv() throws IOException {
		JsonNode web = services(HOSTED).path("web");

		assertThat(web.at("/environment/ARCHSIM_PRIVACY_URL").asString()).isEqualTo("${ARCHSIM_PRIVACY_URL:-}");
	}

	// the guide tells whoever runs the kit to set this in hosted.env (deployment.md).
	// Left out there, it reaches the API empty, which sizes the slots from its heap
	@Test
	void hostedCompose_theApi_takesItsEngineSlotsFromHostedEnv() throws IOException {
		JsonNode api = services(HOSTED).path("api");

		assertThat(api.at("/environment/ARCHSIM_MAX_ENGINE_REQUESTS").asString())
			.isEqualTo("${ARCHSIM_MAX_ENGINE_REQUESTS:-}");
	}

	// the guide tells whoever runs the kit to set these in hosted.env too: how long the
	// server keeps runs, sign-ins and its history. A number cannot be passed on empty, so
	// the kit names each default, which must stay the application's own
	@Test
	void hostedCompose_whatTheApiKeeps_comesFromHostedEnvOrTheApplicationsDefaults() throws IOException {
		JsonNode environment = services(HOSTED).path("api").path("environment");

		assertThat(List.of("ARCHSIM_RUN_HISTORY_LIMIT", "ARCHSIM_SESSION_DAYS", "ARCHSIM_HISTORY_DAYS"))
			.extracting((name) -> environment.path(name).asString())
			.containsExactly("${ARCHSIM_RUN_HISTORY_LIMIT:-" + TestProperties.with().runHistoryLimit() + "}",
					"${ARCHSIM_SESSION_DAYS:-" + TestProperties.signIn().sessionDays() + "}",
					"${ARCHSIM_HISTORY_DAYS:-" + TestProperties.with().historyDays() + "}");
	}

	@Test
	void localCompose_theAppsServices_runTheLocalAppImageWithoutPullingIt() throws IOException {
		JsonNode services = services(LOCAL);

		assertThat(APP_SERVICES)
			.extracting((name) -> services.path(name).path("image").asString(),
					(name) -> services.path(name).path("pull_policy").asString())
			.containsOnly(tuple(LOCAL_APP_IMAGE, "never"));
	}

	@Test
	void localCompose_theWebService_isBuiltFromItsDockerfileUnderALocalName() throws IOException {
		JsonNode web = services(LOCAL).path("web");

		assertThat(List.of(web.path("image").asString(), web.at("/build/context").asString(),
				web.at("/build/dockerfile").asString()))
			.containsExactly("archsim-web:local", ".", "apps/web/Dockerfile");
	}

	@Test
	void localCompose_theAppImage_isTheOneJibBuildsIntoDocker() throws IOException {
		String build = Files.readString(ROOT.resolve("archsim-app/build.gradle.kts"));

		assertThat(build).contains("image = \"" + LOCAL_APP_IMAGE + "\"");
	}

	// a 1 GB heap holds five of the engine's requests at once on the kit's two cores
	// (ADR-0108), and a JVM's container holds its heap and 384 MB besides
	@Test
	void hostedCompose_theApi_hasA1GbHeapInA1408MbContainer() throws IOException {
		JsonNode api = services(HOSTED).path("api");

		assertThat(List.of(api.at("/environment/JAVA_TOOL_OPTIONS").asString(), api.path("mem_limit").asString()))
			.containsExactly("-Xmx${ARCHSIM_API_HEAP:-1g}", "${ARCHSIM_API_MEMORY:-1408m}");
	}

	// a JVM's container holds its heap and 384 MB besides, at the kit's defaults
	@Test
	void hostedCompose_eachJvm_hasItsHeapAnd384MbBesidesInItsContainer() throws IOException {
		JsonNode services = services(HOSTED);

		assertThat(APP_SERVICES).allSatisfy(
				(name) -> assertThat(beyondHeapMegabytes(services.path(name))).as(name).isEqualTo(BEYOND_HEAP_MB));
	}

	// what a JVM's container holds besides its heap, at the kit's defaults
	private static long beyondHeapMegabytes(JsonNode service) {
		String options = service.at("/environment/JAVA_TOOL_OPTIONS").asString();
		return megabytes(service.path("mem_limit").asString(), SIZE) - megabytes(options, HEAP);
	}

	// a size the kit gives, at its default where a setting may override it:
	// "-Xmx${NAME:-1g}" is 1,024 MB and "${NAME:-1408m}" 1,408 MB
	private static long megabytes(String setting, Pattern where) {
		Matcher size = where.matcher(setting);
		assertThat(size.find()).as(setting).isTrue();
		long number = Long.parseLong(size.group(1));
		return "g".equals(size.group(2)) ? number * 1024 : number;
	}

	private static JsonNode services(String file) throws IOException {
		return document(file).path("services");
	}

	private static JsonNode document(String file) throws IOException {
		return YamlDocuments.one(Files.readString(ROOT.resolve(file)));
	}

	// a list of names, such as a service's profiles
	private static List<String> strings(JsonNode list) {
		return list.valueStream().map(JsonNode::asString).toList();
	}

	// a service's networks, as a list of names or a mapping by name
	private static List<String> networks(JsonNode service) {
		JsonNode networks = service.path("networks");
		if (networks.isObject()) {
			return networks.properties().stream().map(Map.Entry::getKey).toList();
		}
		return strings(networks);
	}

	private static boolean builtOrNeverPulled(JsonNode service) {
		return service.has("build") || "never".equals(service.path("pull_policy").asString());
	}

}
