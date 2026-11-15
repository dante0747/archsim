package io.archsim.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.apitests.Examples;
import io.archsim.engine.version.ArchsimVersion;
import io.archsim.engine.yaml.YamlDocuments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The compose files (ADR-0077): {@code docker-compose.yml} runs the release's images, at
 * ArchSim's version, and builds nothing, so Docker alone runs it;
 * {@code docker-compose.local.yml} runs images built from the checkout instead, under
 * names of their own.
 */
class ComposeFileTests {

	private static final Path ROOT = Examples.repoRoot();

	private static final String RELEASE = "docker-compose.yml";

	private static final String LOCAL = "docker-compose.local.yml";

	private static final String REGISTRY = "ghcr.io/dante0747/";

	private static final String LOCAL_APP_IMAGE = "archsim-app:local";

	/** The services that run archsim-app, each in its role. */
	private static final List<String> APP_SERVICES = List.of("migrate", "api", "worker");

	@Test
	void compose_theAppsServices_runTheReleasedAppImageAtArchsimsVersion() throws IOException {
		JsonNode services = services(RELEASE);

		assertThat(APP_SERVICES.stream().map((name) -> services.path(name).path("image").asString()))
			.containsOnly(REGISTRY + "archsim-app:" + ArchsimVersion.PRODUCT);
	}

	@Test
	void compose_theWebService_runsTheReleasedWebImageAtArchsimsVersion() throws IOException {
		JsonNode web = services(RELEASE).path("web");

		assertThat(web.path("image").asString()).isEqualTo(REGISTRY + "archsim-web:" + ArchsimVersion.PRODUCT);
	}

	@Test
	void compose_everyService_isPulledNotBuilt() throws IOException {
		JsonNode services = services(RELEASE);

		assertThat(services.properties()
			.stream()
			.filter((service) -> builtOrNeverPulled(service.getValue()))
			.map(Map.Entry::getKey)).isEmpty();
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

	private static JsonNode services(String file) throws IOException {
		return YamlDocuments.one(Files.readString(ROOT.resolve(file))).path("services");
	}

	private static boolean builtOrNeverPulled(JsonNode service) {
		return service.has("build") || "never".equals(service.path("pull_policy").asString());
	}

}
