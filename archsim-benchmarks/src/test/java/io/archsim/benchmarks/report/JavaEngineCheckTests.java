package io.archsim.benchmarks.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.results.DevChecks;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KneePairs;

import static org.assertj.core.api.Assertions.assertThat;

class JavaEngineCheckTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Engines ENGINES = Engines.read(LAYOUT.engines());

	@Test
	void of_theCommittedChecks_reproducesEveryKeptKneeOfTheFrozenEngine() {
		JsonNode check = JavaEngineCheck.of(LAYOUT.results(), ENGINES).orElseThrow();

		assertThat(check).isEqualTo(JsonText.read("""
				{"version": "1.0.0", "reproduces": "0.16.0", "sameKnees": 65, "keptCalibrations": 65,
				 "text": "Re-simulated, in-sample: engine 1.0.0 (Java) re-simulates every kept calibration \
				with exactly 0.16.0's knees (65 of 65); the latest predictions shown are 0.16.0's, which \
				1.0.0 reproduces."}"""));
	}

	@Test
	void of_theCommittedResults_showTheFrozenEnginesPredictions() {
		JsonNode check = JavaEngineCheck.of(LAYOUT.results(), ENGINES).orElseThrow();

		assertThat(LatestRuns.inPageOrder())
			.map((experiment) -> LatestRuns.load(LAYOUT.results(), experiment.id(), KeptRun.PREDICTIONS)
				.orElseThrow()
				.get("engineVersion"))
			.containsOnly(check.get("reproduces"));
	}

	@Test
	void of_theCommittedChecks_countsTheSameKneesAsTheParityPage() throws IOException {
		JsonNode check = JavaEngineCheck.of(LAYOUT.results(), ENGINES).orElseThrow();

		String parity = Files.readString(LAYOUT.validation().resolve("java-parity.md"), StandardCharsets.UTF_8);

		assertThat(parity).contains(", " + check.get("sameKnees").intValue() + " the same;");
	}

	@Test
	void of_aKneeTheJavaEngineMissed_saysHowManyItReproduces(@TempDir Path results) throws IOException {
		write(results.resolve("dev"), KneePairs.PYTHON_KNEES, 1000.0, 2000.0, 900.0);
		write(results.resolve("dev"), KneePairs.JAVA_KNEES, 1000.0, 2100.0);

		JsonNode check = JavaEngineCheck.of(results, ENGINES).orElseThrow();

		assertThat(check.get("sameKnees").intValue()).isEqualTo(1);
		assertThat(check.get("keptCalibrations").intValue()).isEqualTo(3);
		assertThat(check.get("text").stringValue()).isEqualTo("Re-simulated, in-sample: engine 1.0.0 (Java) "
				+ "re-simulates 1 of 3 kept calibrations with exactly 0.16.0's knees; the latest predictions shown are "
				+ "0.16.0's.");
	}

	@Test
	void of_noChecks_isEmpty(@TempDir Path results) {
		Optional<ObjectNode> check = JavaEngineCheck.of(results, ENGINES);

		assertThat(check).isEmpty();
	}

	@Test
	void block_theCommittedChecks_isTheValidationPagesSentence() throws IOException {
		String block = JavaEngineCheck.block(LAYOUT.results(), ENGINES);

		assertThat(Files.readString(LAYOUT.validationPage(), StandardCharsets.UTF_8)).contains(block);
		assertThat(block).endsWith("reproduces ([java-parity.md](validation/java-parity.md)).");
	}

	// one development check of kafka per knee, named by its place
	private static void write(Path dev, String engine, double... knees) throws IOException {
		for (int i = 0; i < knees.length; i++) {
			DevChecks.write(dev, engine, new DevChecks.Check("kafka", String.valueOf(i), knees[i], 2.0));
		}
	}

}
