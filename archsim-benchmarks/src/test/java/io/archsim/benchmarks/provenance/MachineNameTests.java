package io.archsim.benchmarks.provenance;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The short name of the machine a run measured on: the CPU's model without its vendor's
 * words, never the host's name, which can identify a person.
 */
class MachineNameTests {

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = { "11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz | i7-1165g7",
			"AMD EPYC 7763 64-Core Processor | epyc-7763-64-core" })
	void id_aCpuModel_isItsModelWithoutTheVendorsWords(String cpu, String id) {
		String named = MachineName.id(Map.of(), cpu);

		assertThat(named).isEqualTo(id);
	}

	@Test
	void id_noCpuModel_isUnknown() {
		String named = MachineName.id(Map.of(), "");

		assertThat(named).isEqualTo("unknown");
	}

	@Test
	void id_aMachineNamedInTheEnvironment_isThatName() {
		String named = MachineName.id(Map.of("BENCH_MACHINE", "ci-linux-8cpu"), "anything");

		assertThat(named).isEqualTo("ci-linux-8cpu");
	}

}
