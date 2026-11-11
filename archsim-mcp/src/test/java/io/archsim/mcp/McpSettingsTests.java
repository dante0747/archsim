package io.archsim.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.engine.simulation.SimulationLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The server's settings as the Python server read them from its environment: the
 * templates' folder, named or found above the server's files, and each run's limits.
 */
class McpSettingsTests {

	@Test
	void of_noVariables_findsTheExamplesAboveTheServer(@TempDir Path checkout) throws IOException {
		Path examples = checkoutExamples(checkout);

		McpSettings settings = McpSettings.of(Map.of(), installed(checkout));

		assertThat(settings.examples()).isEqualTo(examples);
	}

	@Test
	void of_noVariables_isThePythonServersLimits(@TempDir Path folder) {
		McpSettings settings = McpSettings.of(Map.of(), folder);

		assertThat(settings.limits()).isEqualTo(new SimulationLimits(2_000_000, 120));
	}

	@Test
	void simulationModel_examplesInACheckout_isTheCheckoutsPage(@TempDir Path checkout) throws IOException {
		checkoutExamples(checkout);

		McpSettings settings = McpSettings.of(Map.of(), installed(checkout));

		assertThat(settings.simulationModel()).isEqualTo(checkout.resolve("docs/simulation-model.md"));
	}

	@Test
	void of_examplesVariable_namesTheExamples(@TempDir Path folder) {
		McpSettings settings = McpSettings.of(Map.of("ARCHSIM_EXAMPLES_DIR", folder.toString()), folder);

		assertThat(settings.examples()).isEqualTo(folder);
	}

	@Test
	void of_limitVariables_setTheLimitsWithSpacesAroundThem(@TempDir Path folder) {
		McpSettings settings = McpSettings
			.of(Map.of("ARCHSIM_MCP_MAX_REQUESTS", " 1000 ", "ARCHSIM_MCP_MAX_WALL_SECONDS", " 2.5 "), folder);

		assertThat(settings.limits()).isEqualTo(new SimulationLimits(1000, 2.5));
	}

	@Test
	void of_examplesVariableEmpty_findsTheExamplesAsUnset(@TempDir Path checkout) throws IOException {
		// as Python tested it by its truth
		Path examples = checkoutExamples(checkout);

		McpSettings settings = McpSettings.of(Map.of("ARCHSIM_EXAMPLES_DIR", ""), installed(checkout));

		assertThat(settings.examples()).isEqualTo(examples);
	}

	@Test
	void of_anExamplesFolderWithoutJson_isNotTheTemplates(@TempDir Path folder) throws IOException {
		Files.createDirectories(folder.resolve("examples"));

		McpSettings settings = McpSettings.of(Map.of(), folder);

		assertThat(settings.examples()).isNull();
	}

	@Test
	void simulationModel_noTemplatesFolder_isNone(@TempDir Path folder) {
		McpSettings settings = McpSettings.of(Map.of(), folder);

		assertThat(settings.simulationModel()).isNull();
	}

	@Test
	void of_aLimitThatIsNotANumber_isRefused(@TempDir Path folder) {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> McpSettings.of(Map.of("ARCHSIM_MCP_MAX_REQUESTS", "many"), folder));
	}

	// a checkout's examples folder, holding a template
	private static Path checkoutExamples(Path checkout) throws IOException {
		Path examples = Files.createDirectories(checkout.resolve("examples"));
		Files.writeString(examples.resolve("queueing-lab.json"), "{}");
		return examples;
	}

	// where installDist puts the server's files in a checkout
	private static Path installed(Path checkout) throws IOException {
		return Files.createDirectories(checkout.resolve("archsim-mcp/build/install/archsim-mcp/lib"));
	}

}
