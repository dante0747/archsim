package io.archsim.benchmarks.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.model.text.PythonFormat;

/**
 * k6 in its container ({@code compose.yaml}'s {@code load} profile, {@code k6/load.js}),
 * which writes its end-of-run summary to a folder the harness reads.
 */
public final class K6 {

	private final Compose compose;

	private final Path summaries;

	/**
	 * k6 on a stack.
	 * @param compose the stack's compose
	 * @param summaries the folder k6's summaries land in ({@code results/.k6})
	 */
	public K6(Compose compose, Path summaries) {
		this.compose = compose;
		this.summaries = summaries;
	}

	/**
	 * Sends traffic and waits for k6 to end.
	 * @param traffic the traffic
	 * @param window its warm-up and measured seconds
	 * @param name the summary's name
	 * @return k6's summary
	 */
	public JsonNode run(Traffic traffic, Window window, String name) {
		Path summary = prepare(name);
		this.compose.output("--profile", "load", "run", "--rm", "-e", "TARGET=" + traffic.target(), "-e",
				"MIX=" + traffic.mix(), "-e", "RATE=" + PythonFormat.format(traffic.rate(), "g"), "-e",
				"WARMUP_S=" + window.warmup(), "-e", "DURATION_S=" + window.duration(), "-e", "KEYS=" + traffic.keys(),
				"-e", "SKEW=" + PythonFormat.format(traffic.skew(), "g"), "-e", "TIMEOUT_MS=" + Protocol.TIMEOUT_MS,
				"-e", "VUS=" + traffic.virtualUsers(), "-e", "OUT=" + name, "k6", "run", "-q", "/scripts/load.js");
		return ResultFiles.read(summary)
			.orElseThrow(() -> new IllegalStateException("k6 wrote no summary to " + summary));
	}

	/**
	 * Deletes a summary k6 wrote: a smoke test keeps nothing.
	 * @param name the summary's name
	 */
	public void forget(String name) {
		try {
			Files.deleteIfExists(this.summaries.resolve(name + ".json"));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// the grafana/k6 image runs as uid 12345: on Linux the bind-mounted folder must
	// be writable by it, or handleSummary cannot write the summary; Docker Desktop on
	// Windows maps ownership itself
	private Path prepare(String name) {
		try {
			Files.createDirectories(this.summaries);
			if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
				Files.setPosixFilePermissions(this.summaries, PosixFilePermissions.fromString("rwxrwxrwx"));
			}
			Path summary = this.summaries.resolve(name + ".json");
			Files.deleteIfExists(summary);
			return summary;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * k6's seconds of warm-up, then of measurement.
	 *
	 * @param warmup seconds of warm-up, not recorded
	 * @param duration seconds recorded
	 */
	public record Window(int warmup, int duration) {

	}

}
