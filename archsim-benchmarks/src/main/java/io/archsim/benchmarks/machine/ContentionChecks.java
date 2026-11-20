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
 * The contention checks ({@code contention}) in {@code results/envcheck}: each names the
 * machine it measured ({@code environment.machineId}), and only a machine's own fit it.
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
		Path folder = folder(results);
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
	 * The folder the checks are in.
	 * @param results the results folder
	 * @return its {@code envcheck}
	 */
	public static Path folder(Path results) {
		return results.resolve("envcheck");
	}

	/**
	 * A machine's fit from its own latest check with a probe and its latest quiet one
	 * with a service part.
	 * @param results the results folder
	 * @param machineId the machine ({@code MachineName}), whose checks alone count
	 * @return the fit; empty without a check of the machine with a probe or a quiet one
	 */
	static Optional<MachineFit> latestFit(Path results, String machineId) {
		List<JsonNode> checks = in(results).reversed()
			.stream()
			.map((file) -> ResultFiles.read(file).orElseThrow())
			.filter((check) -> machineId.equals(check.at("/environment/machineId").stringValue("")))
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
