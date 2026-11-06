package io.archsim.engine.check;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.archsim.parity.GoldenEntry;
import io.archsim.parity.GoldenFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thresholds {@code archsim check} uses unless told otherwise are the frozen
 * engine's: the golden files record its checks with {@code asdict(Thresholds())}.
 */
class ThresholdsTests {

	@Test
	void defaults_comparedWithTheGoldenFiles_areTheFrozenEnginesThresholds() {
		GoldenEntry check = GoldenFile.read(GoldenFile.templates().getFirst())
			.entries("archsim_engine.check.run_check")
			.getFirst();
		Map<String, Double> recorded = new LinkedHashMap<>();
		check.input("thresholds")
			.properties()
			.forEach((threshold) -> recorded.put(threshold.getKey(), threshold.getValue().doubleValue()));

		assertThat(Thresholds.DEFAULTS.byPythonName()).containsExactlyInAnyOrderEntriesOf(recorded);
	}

	@Test
	void byPythonName_ofTheDefaults_isInTheOrderAsdictWritesThem() {
		assertThat(Thresholds.DEFAULTS.byPythonName().keySet()).containsExactly("p95_increase", "success_drop",
				"cost_increase", "knee_drop");
	}

}
