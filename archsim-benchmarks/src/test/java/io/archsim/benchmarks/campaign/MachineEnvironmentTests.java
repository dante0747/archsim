package io.archsim.benchmarks.campaign;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The machine as {@code environment.json} records it: the operating system as Python's
 * {@code platform.platform()} wrote it, Windows' power mode, the service's image.
 */
class MachineEnvironmentTests {

	// what Windows 11's ver prints, platform.platform() naming it release 11
	@Test
	void windowsPlatform_windows11_isAsPythonNamedIt() {
		assertThat(MachineEnvironment.windowsPlatform("\r\nMicrosoft Windows [Version 10.0.26300.7171]\r\n"))
			.contains("Windows-11-10.0.26300-SP0");
	}

	@Test
	void windowsPlatform_windows10_isRelease10() {
		assertThat(MachineEnvironment.windowsPlatform("Microsoft Windows [Version 10.0.19045.5737]"))
			.contains("Windows-10-10.0.19045-SP0");
	}

	@Test
	void windowsPlatform_noVersion_isUnknown() {
		assertThat(MachineEnvironment.windowsPlatform("")).isEmpty();
	}

	@Test
	void powerModeOf_bestPerformance_isNamed() {
		String query = """

				HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Power\\User\\PowerSchemes
				    ActiveOverlayAcPowerScheme    REG_SZ    ded574b5-45a0-4f42-8737-46345c09c238

				""";

		assertThat(MachineEnvironment.powerModeOf(query)).contains("Best performance");
	}

	@Test
	void powerModeOf_aSchemeWithoutAName_isTheScheme() {
		assertThat(MachineEnvironment.powerModeOf("ActiveOverlayAcPowerScheme    REG_SZ    ABC-123"))
			.contains("ABC-123");
	}

	@Test
	void powerModeOf_noScheme_isUnknown() {
		assertThat(MachineEnvironment.powerModeOf("ERROR: The system was unable to find the value.")).isEmpty();
	}

	@Test
	void image_aChain_isItsGatewaysAndApis() {
		assertThat(MachineEnvironment.image(Experiments.standard().get("chain"))).endsWith(" (gateway and API)");
	}

	@Test
	void image_javaMixed_isTheJvmService() {
		assertThat(MachineEnvironment.image(Experiments.standard().get("java-mixed"))).startsWith("eclipse-temurin:21");
	}

}
