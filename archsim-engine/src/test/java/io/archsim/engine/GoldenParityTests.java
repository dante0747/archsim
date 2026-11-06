package io.archsim.engine;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.archsim.parity.GoldenEntry;
import io.archsim.parity.GoldenFile;
import io.archsim.parity.Parity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every recorded call of a Python function this module ports, in every golden file, gives
 * the same output or the same error in Java: the exit check of stage 3. The few entries
 * whose words are a library's are listed in {@link GoldenDepartures}.
 */
@DisplayName("The engine matches the frozen Python engine's golden files")
class GoldenParityTests {

	private static final List<GoldenFile> FILES = GoldenFile.all().stream().map(GoldenFile::read).toList();

	@TestFactory
	Stream<DynamicContainer> portedFunction_everyRecordedCall_givesTheSameOutputOrError() {
		return FILES.stream()
			.map((file) -> DynamicContainer.dynamicContainer(file.name(), ported(file).stream()
				.map((entry) -> DynamicTest.dynamicTest(entry.pointer(), () -> check(entry)))));
	}

	@Test
	void departures_everyEntryListed_isARecordedCallOfAPortedFunction() {
		List<GoldenDepartures.Recorded> recorded = FILES.stream()
			.flatMap((file) -> ported(file).stream())
			.map(GoldenDepartures.Recorded::of)
			.toList();
		assertThat(recorded).containsAll(GoldenDepartures.entries());
	}

	@Test
	void goldenFiles_everyPortedFunction_hasRecordedCallsToCheck() {
		List<String> checked = FILES.stream()
			.flatMap((file) -> ported(file).stream())
			.map(GoldenEntry::fn)
			.distinct()
			.toList();
		assertThat(checked).containsExactlyInAnyOrderElementsOf(EngineFunctions.names());
	}

	/**
	 * Checks an entry exactly, or as its departure says.
	 * @param entry the entry
	 */
	private static void check(GoldenEntry entry) {
		GoldenDepartures.of(entry)
			.ifPresentOrElse((departure) -> departure.check(entry, EngineFunctions::call),
					() -> Parity.check(entry, EngineFunctions::call));
	}

	private static List<GoldenEntry> ported(GoldenFile file) {
		return file.entries().stream().filter((entry) -> EngineFunctions.names().contains(entry.fn())).toList();
	}

}
