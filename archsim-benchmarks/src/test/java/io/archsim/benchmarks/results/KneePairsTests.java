package io.archsim.benchmarks.results;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class KneePairsTests {

	private static final String PYTHON = KneePairs.PYTHON_KNEES;

	private static final String JAVA = KneePairs.JAVA_KNEES;

	@Test
	void of_aCalibrationBothEnginesChecked_pairsItsKneesByItsFile(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-10-26T08-23-18Z", 1015.0));

		List<ObjectNode> rows = KneePairs.of(dev);

		assertThat(rows).containsExactly(JsonText.object("""
				{"experiment": "pg-mixed", "calibration": "2026-10-26T08-23-18Z", "python": 1000.0, "java": 1015.0,
				 "checked": true, "resimulated": true, "sameInputs": true, "withinTwoPercent": true}"""));
	}

	@Test
	void of_aJavaCheckWithoutAFrozenEngineOne_isListedAndMisses(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-11-03T10-00-00Z", 800.0));

		List<ObjectNode> rows = KneePairs.of(dev);

		assertThat(rows)
			.extracting((row) -> row.get("calibration").stringValue(), (row) -> row.get("checked").booleanValue(),
					(row) -> row.get("withinTwoPercent").booleanValue())
			.containsExactly(tuple("2026-10-26T08-23-18Z", true, true), tuple("2026-11-03T10-00-00Z", false, false));
	}

	@Test
	void of_aCalibrationJavaDidNotResimulate_misses(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T12-22-50Z", 500.0));

		JsonNode row = KneePairs.of(dev).getFirst();

		assertThat(row.get("java").isNull()).isTrue();
		assertThat(row.get("resimulated").booleanValue()).isFalse();
		assertThat(row.get("sameInputs").isNull()).isTrue();
		assertThat(row.get("withinTwoPercent").booleanValue()).isFalse();
	}

	@Test
	void of_aCalibrationJavaDidNotResimulateWithoutAPythonKnee_misses(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T12-22-50Z", null));

		assertThat(KneePairs.of(dev).getFirst().get("withinTwoPercent").booleanValue()).isFalse();
	}

	@Test
	void of_twoChecksThatFoundNoKnee_agree(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T12-22-50Z", null));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-10-26T12-22-50Z", null));

		assertThat(KneePairs.of(dev).getFirst().get("withinTwoPercent").booleanValue()).isTrue();
	}

	@Test
	void of_aPairWhoseDerivedInputsDiffer_isNotSameInputs(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, new DevChecks.Check("pg-mixed", "2026-10-26T08-23-18Z", 1000.0, 2.0));
		DevChecks.write(dev, JAVA, new DevChecks.Check("pg-mixed", "2026-10-26T08-23-18Z", 1000.0, 2.1));

		List<ObjectNode> rows = KneePairs.of(dev);

		assertThat(rows.getFirst().get("sameInputs").booleanValue()).isFalse();
	}

	@Test
	void of_aKneeTwoPercentOff_isWithin(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-10-26T08-23-18Z", 1020.0));

		assertThat(KneePairs.of(dev).getFirst().get("withinTwoPercent").booleanValue()).isTrue();
	}

	@Test
	void of_aKneeFurtherThanTwoPercentOff_isNotWithin(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-10-26T08-23-18Z", 1020.5));

		assertThat(KneePairs.of(dev).getFirst().get("withinTwoPercent").booleanValue()).isFalse();
	}

	// Python sorts paths part by part, where a string sort puts pg-mixed/ first
	@Test
	void of_experimentsWhereOneNameBeginsTheOther_areInPythonsOrder(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, new DevChecks.Check("pg-mixed", "2026-10-26T08-23-18Z", 1000.0, 2.0));
		DevChecks.write(dev, PYTHON, new DevChecks.Check("pg", "2026-10-26T08-23-18Z", 1000.0, 2.0));

		List<ObjectNode> rows = KneePairs.of(dev);

		assertThat(rows).extracting((row) -> row.get("experiment").stringValue()).containsExactly("pg", "pg-mixed");
	}

	// Python's Path.parent of a bare file name is "."
	@Test
	void of_aCheckOutsideAnyExperimentFolder_isInExperimentDot(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, new DevChecks.Check("", "2026-10-26T08-23-18Z", 1000.0, 2.0));

		JsonNode row = KneePairs.of(dev).getFirst();

		assertThat(row.get("experiment").stringValue()).isEqualTo(".");
	}

	@Test
	void of_oneEngineFoundAKneeAndTheOtherNone_isNotWithinTwoPercent(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-10-26T08-23-18Z", null));

		assertThat(KneePairs.of(dev).getFirst().get("withinTwoPercent").booleanValue()).isFalse();
	}

	@Test
	void sameKnee_twoChecksThatFoundNoKnee_isTheSameKnee(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T12-22-50Z", null));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-10-26T12-22-50Z", null));

		assertThat(KneePairs.sameKnee(KneePairs.of(dev).getFirst())).isTrue();
	}

	@Test
	void sameKnee_kneesOnePercentApart_isNotTheSameKnee(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));
		DevChecks.write(dev, JAVA, DevChecks.Check.of("2026-10-26T08-23-18Z", 1010.0));

		assertThat(KneePairs.sameKnee(KneePairs.of(dev).getFirst())).isFalse();
	}

	@Test
	void sameKnee_aCalibrationJavaDidNotResimulate_isNotTheSameKnee(@TempDir Path dev) throws IOException {
		DevChecks.write(dev, PYTHON, DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));

		assertThat(KneePairs.sameKnee(KneePairs.of(dev).getFirst())).isFalse();
	}

}
