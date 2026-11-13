package io.archsim.benchmarks.machine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.model.text.PythonText;

/**
 * This machine's contention checks ({@code contention}), in {@code results/envcheck}.
 */
public final class ContentionChecks {

	private static final String SUFFIX = "-contention.json";

	private ContentionChecks() {
	}

	/**
	 * The checks, oldest first.
	 * @param results the results folder
	 * @return each check's file
	 */
	public static List<Path> in(Path results) {
		Path folder = results.resolve("envcheck");
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter((file) -> file.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The machine's fit from the latest check with a probe and the latest quiet one with
	 * a service part.
	 * @param results the results folder
	 * @return the fit; empty without a check with a probe or a quiet one
	 */
	static Optional<MachineFit> latestFit(Path results) {
		List<JsonNode> checks = in(results).reversed()
			.stream()
			.map((file) -> ResultFiles.read(file).orElseThrow())
			.toList();
		OptionalInt probe = IntStream.range(0, checks.size())
			.filter((index) -> checks.get(index).path("probeRounds").valueStream().anyMatch(PythonText::truthy))
			.findFirst();
		if (probe.isEmpty()) {
			return Optional.empty();
		}
		JsonNode probed = checks.get(probe.getAsInt());
		// the probe's own check among the service parts:
		// its own service part, not another check's
		return IntStream.range(0, checks.size())
			.mapToObj((index) -> (index == probe.getAsInt()) ? ContentionFit.of(probed)
					: ContentionFit.of(probed, checks.get(index)))
			.flatMap(Optional::stream)
			.findFirst();
	}

}
