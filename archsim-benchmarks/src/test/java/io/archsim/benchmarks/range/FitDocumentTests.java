package io.archsim.benchmarks.range;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fit's file besides the fit: how the ranges the runs were predicted with held their
 * measured knees, each nominal coverage on its own.
 */
class FitDocumentTests {

	// a check whose counts and runs' ranges the earlier ranges' count does not read
	private static final RangeCheck.Result CHECK = new RangeCheck.Result(JsonNodeFactory.instance.objectNode(),
			List.of());

	@Test
	void check_rangesOfTwoNominalCoverages_areCountedApart() {
		List<CountedRun> runs = List.of(run(0.8, true), run(0.5, false), run(0.8, true), run(0.5, true),
				run(0.8, false));

		JsonNode before = FitDocument.check(CHECK, runs).get("before");

		assertThat(before).isEqualTo(JsonText.read("""
				[{"nominalCoverage": 0.5, "inRange": 1, "withRange": 2},
				 {"nominalCoverage": 0.8, "inRange": 2, "withRange": 3}]"""));
	}

	@Test
	void check_aRangeWithoutANominalCoverage_isLeftOut() {
		List<CountedRun> runs = List.of(run(0.8, true), run(JsonText.read("{\"containsMeasured\": true}")));

		JsonNode before = FitDocument.check(CHECK, runs).get("before");

		assertThat(before).isEqualTo(JsonText.read("[{\"nominalCoverage\": 0.8, \"inRange\": 1, \"withRange\": 1}]"));
	}

	@Test
	void check_runsWithoutARange_areLeftOut() {
		List<CountedRun> runs = List.of(run(0.8, true), run(JsonText.read("null")));

		JsonNode before = FitDocument.check(CHECK, runs).get("before");

		assertThat(before).isEqualTo(JsonText.read("[{\"nominalCoverage\": 0.8, \"inRange\": 1, \"withRange\": 1}]"));
	}

	// a run predicted with a range of a nominal coverage, which held its knee or not
	private static CountedRun run(double coverage, boolean held) {
		return run(JsonText.read("{\"nominalCoverage\": " + coverage + ", \"containsMeasured\": " + held + "}"));
	}

	// a run predicted with a range, or None
	private static CountedRun run(JsonNode before) {
		return new CountedRun("pg-mixed", "pg-mixed", JsonText.read("null"), JsonText.read("null"),
				JsonText.read("null"), JsonNodeFactory.instance.objectNode(), 0.0, before);
	}

}
