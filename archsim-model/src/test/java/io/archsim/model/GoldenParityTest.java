package io.archsim.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.archsim.parity.GoldenEntry;
import io.archsim.parity.GoldenFile;
import io.archsim.parity.Parity;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every recorded call of a Python function this module ports, in every golden file, gives
 * the same output or the same error in Java: the exit check of stage 1.
 */
@DisplayName("The model matches the frozen Python engine's golden files")
class GoldenParityTest {

	private static final List<GoldenFile> FILES = GoldenFile.all().stream().map(GoldenFile::read).toList();

	@TestFactory
	Stream<DynamicContainer> every_ported_function_gives_the_recorded_output_or_error() {
		return FILES.stream()
			.map(file -> DynamicContainer.dynamicContainer(file.name(),
					ported(file).stream()
						.map(entry -> DynamicTest.dynamicTest(entry.pointer(),
								() -> Parity.check(entry, ModelFunctions::call, ModelFunctions::error)))));
	}

	@Test
	void every_ported_function_has_recorded_calls_to_check() {
		List<String> checked = FILES.stream()
			.flatMap(file -> ported(file).stream())
			.map(GoldenEntry::fn)
			.distinct()
			.toList();
		assertThat(checked).containsExactlyInAnyOrderElementsOf(ModelFunctions.names());
	}

	private static List<GoldenEntry> ported(GoldenFile file) {
		return file.entries().stream().filter(entry -> ModelFunctions.names().contains(entry.fn())).toList();
	}

}
