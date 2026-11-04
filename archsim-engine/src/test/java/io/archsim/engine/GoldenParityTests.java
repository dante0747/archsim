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
 * the same output or the same error in Java: the exit check of stage 3.
 */
@DisplayName("The engine matches the frozen Python engine's golden files")
class GoldenParityTests {

	private static final List<GoldenFile> FILES = GoldenFile.all().stream().map(GoldenFile::read).toList();

	@TestFactory
	Stream<DynamicContainer> portedFunction_everyRecordedCall_givesTheSameOutputOrError() {
		return FILES.stream()
			.map((file) -> DynamicContainer.dynamicContainer(file.name(),
					ported(file).stream()
						.map((entry) -> DynamicTest.dynamicTest(entry.pointer(),
								() -> Parity.check(entry, EngineFunctions::call)))));
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

	private static List<GoldenEntry> ported(GoldenFile file) {
		return file.entries().stream().filter((entry) -> EngineFunctions.names().contains(entry.fn())).toList();
	}

}
