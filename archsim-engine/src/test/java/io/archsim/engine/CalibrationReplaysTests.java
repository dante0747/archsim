package io.archsim.engine;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.archsim.parity.GoldenEntry;
import io.archsim.parity.GoldenFile;
import io.archsim.parity.Parity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden files' calibrations that replay their result, replayed on the Java simulator
 * as the app replays them (ADR-0053) instead of from the reports the frozen engine
 * recorded: each replay gives the recorded report, and each calibration the recorded
 * outcome.
 */
class CalibrationReplaysTests {

	private static final List<GoldenEntry> REPLAYING = GoldenFile.all()
		.stream()
		.map(GoldenFile::read)
		.flatMap((file) -> file.entries().stream())
		.filter((entry) -> CalibrateFunctions.PORTED.containsKey(entry.fn()))
		.filter((entry) -> !entry.input("simulations").isEmpty())
		.toList();

	@TestFactory
	Stream<DynamicTest> calibrate_recordedReplaySimulatedInJava_givesTheRecordedOutcome() {
		return REPLAYING.stream()
			.map((entry) -> DynamicTest.dynamicTest(entry.pointer(),
					() -> Parity.check(entry, CalibrateFunctions::replayedInJava)));
	}

	@Test
	void goldenFiles_bothCalibrations_haveReplaysToCheck() {
		List<String> calibrations = REPLAYING.stream().map(GoldenEntry::fn).distinct().toList();

		assertThat(calibrations).containsExactlyInAnyOrderElementsOf(CalibrateFunctions.PORTED.keySet());
	}

}
