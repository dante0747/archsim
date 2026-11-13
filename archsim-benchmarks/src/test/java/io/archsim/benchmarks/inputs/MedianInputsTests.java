package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inputs from a calibration's replicates: each input whole from the replicate whose
 * value is the median, records without a value number by number, and an input only some
 * replicates have from those that have it.
 */
class MedianInputsTests {

	private static final String REPLICATES = """
			[{"serviceTimeMs": {"value": {"kind": "lognormal", "median": 0.2, "p95": 0.5}, "how": "rep 0"},
			  "clientLinkLatencyMs": {"value": 0.1, "how": "x"},
			  "measuredLowLoad": {"rateRps": 1483, "p50": 0.2}},
			 {"serviceTimeMs": {"value": {"kind": "lognormal", "median": 0.3, "p95": 0.4}, "how": "rep 1"},
			  "clientLinkLatencyMs": {"value": 0.3, "how": "x"},
			  "measuredLowLoad": {"rateRps": 1483, "p50": 0.6}},
			 {"serviceTimeMs": {"value": {"kind": "lognormal", "median": 0.25, "p95": 0.9}, "how": "rep 2"},
			  "clientLinkLatencyMs": {"value": 0.2, "how": "x"},
			  "measuredLowLoad": {"rateRps": 1483, "p50": 0.4}}]""";

	// its p95 comes with its median
	@Test
	void of_threeReplicates_takesEachInputWholeFromItsMedianReplicate() {
		ObjectNode median = MedianInputs.of(replicates(REPLICATES));

		assertThat(median.get("serviceTimeMs")).isEqualTo(JsonText.read("""
				{"value": {"kind": "lognormal", "median": 0.25, "p95": 0.9},
				 "how": "the median of 3 replicates (third): rep 2"}"""));
	}

	@Test
	void of_threeReplicates_takesAValuesMedian() {
		ObjectNode median = MedianInputs.of(replicates(REPLICATES));

		assertThat(median.get("clientLinkLatencyMs").get("value").doubleValue()).isEqualTo(0.2);
	}

	@Test
	void of_threeReplicates_takesARecordWithoutAValueNumberByNumber() {
		ObjectNode median = MedianInputs.of(replicates(REPLICATES));

		assertThat(median.get("measuredLowLoad")).isEqualTo(JsonText.read("{\"rateRps\": 1483, \"p50\": 0.4}"));
	}

	// a disturbed replicate (a slow outlier) never sets an input
	@Test
	void of_aSlowOutlier_isNeverTheMedian() {
		List<ObjectNode> replicates = replicates(REPLICATES);
		replicates.getFirst().set("serviceTimeMs", JsonText.read("""
				{"value": {"kind": "lognormal", "median": 0.9, "p95": 9.0}, "how": "x"}"""));

		ObjectNode median = MedianInputs.of(replicates);

		assertThat(median.get("serviceTimeMs").get("value").get("median").doubleValue()).isEqualTo(0.3);
	}

	@Test
	void primary_aDistributionWithoutAMedian_isItsMean() {
		assertThat(MedianInputs.primary(JsonText.read("{\"kind\": \"exponential\", \"mean\": 0.005}")))
			.isEqualTo(0.005);
	}

	@Test
	void primary_aBoolean_isNone() {
		assertThat(MedianInputs.primary(JsonText.read("true"))).isNull();
	}

	// cache-aside's latest v6 run: no mark-compact in the first replicate's pass
	@Test
	void of_aSpacingTheFirstReplicateLacks_isTheMedianOfThoseThatHaveIt() {
		ObjectNode median = MedianInputs.of(replicates("""
				[{"gcPauseMs": {"value": 0, "how": "x"}},
				 {"gcPauseMs": {"value": 9.9517, "how": "x"}, "gcEveryRequests": {"value": 48766, "how": "x"}},
				 {"gcPauseMs": {"value": 9.7368, "how": "x"}, "gcEveryRequests": {"value": 32510, "how": "x"}}]"""));

		assertThat(median).isEqualTo(JsonText.read("""
				{"gcPauseMs": {"value": 9.7368, "how": "the median of 3 replicates (third): x"},
				 "gcEveryRequests": {"value": 32510,
				                     "how": "the median of the 2 of 3 replicates with it (third): x"}}"""));
	}

	// no pause at the median: no spacing either
	@Test
	void of_noPauseAtTheMedian_leavesTheSpacingOut() {
		ObjectNode median = MedianInputs.of(replicates("""
				[{"gcPauseMs": {"value": 0, "how": "x"}},
				 {"gcPauseMs": {"value": 0, "how": "x"}},
				 {"gcPauseMs": {"value": 9.9166, "how": "x"}, "gcEveryRequests": {"value": 33500, "how": "x"}}]"""));

		assertThat(median).isEqualTo(JsonText.read("""
				{"gcPauseMs": {"value": 0, "how": "the median of 3 replicates (first): x"}}"""));
	}

	private static List<ObjectNode> replicates(String json) {
		return new ArrayList<>(JsonText.read(json).valueStream().map((replicate) -> (ObjectNode) replicate).toList());
	}

}
