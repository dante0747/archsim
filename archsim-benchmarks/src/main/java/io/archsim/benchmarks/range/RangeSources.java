package io.archsim.benchmarks.range;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The calibrations the range is fitted on: for each in-sample experiment, its kept runs
 * from protocol v5 on and its calibration-only trials ({@code results/trials/}), each
 * under the same conditions as the experiment's latest kept run. Held-out experiments
 * have none (they are sealed). A trial folder with a {@code DISCARDED} file is left out:
 * its calibration was taken under conditions it cannot stand for (W26's cache-aside trial
 * of 1 Nov ran with the CPUs at about half their usual clock).
 */
final class RangeSources {

	/**
	 * The range is fitted on calibrations from protocol v5 on: earlier protocols derived
	 * the inputs from passes of another kind.
	 */
	static final int MIN_PROTOCOL = 5;

	private static final Pattern PROTOCOL = Pattern.compile("v(\\d+)");

	private static final Pattern METHOD_PROTOCOL = Pattern.compile("\\(v(\\d+)\\)");

	private static final List<String> CONDITIONS = List.of("cpu", "os", "powerMode");

	private RangeSources() {
	}

	/**
	 * The sources under a results folder.
	 * @param results the results folder
	 * @return the calibrations, experiment by experiment, each one's kept runs before its
	 * trials
	 */
	static List<Source> of(Path results) {
		List<Path> trials = trials(results);
		List<KeptRun> kept = KeptRuns.of(results);
		List<Source> sources = new ArrayList<>();
		for (Experiment experiment : Experiments.standard().all()) {
			List<KeptRun> runs = kept.stream().filter((run) -> run.experiment().equals(experiment.id())).toList();
			if (experiment.isHeldOut() || runs.isEmpty()) {
				continue;
			}
			List<Source> found = new ArrayList<>();
			runs.forEach((run) -> found.add(Source.of(results, experiment.id(), "kept", run.directory())));
			trials.stream()
				.map((trial) -> Source.of(results, experiment.id(), "trial", trial))
				.filter((trial) -> experiment.id().equals(trial.calibration().path("experiment").stringValue("")))
				.forEach(found::add);
			List<JsonNode> latest = conditions(runs.getLast().directory());
			found.stream()
				.filter((source) -> PythonText.truthy(source.calibration())
						&& protocolNumber(source.calibration()) >= MIN_PROTOCOL)
				.filter((source) -> sameConditions(conditions(source.directory()), latest))
				.forEach(sources::add);
		}
		return sources;
	}

	/**
	 * A calibration's protocol as a number: 5 for {@code v5}, else the {@code (vN)} its
	 * method names, else 1 (before either was recorded).
	 * @param calibration the calibration, or None
	 * @return the protocol's number
	 */
	static int protocolNumber(JsonNode calibration) {
		JsonNode protocol = calibration.path("protocol");
		Matcher named = PROTOCOL.matcher(PythonText.truthy(protocol) ? PythonText.str(protocol) : "");
		if (named.matches()) {
			return Integer.parseInt(named.group(1));
		}
		JsonNode method = calibration.path("method");
		Matcher inMethod = METHOD_PROTOCOL.matcher(PythonText.truthy(method) ? method.stringValue("") : "");
		return inMethod.find() ? Integer.parseInt(inMethod.group(1)) : 1;
	}

	/**
	 * What makes two calibrations comparable: the machine, its OS build and power mode.
	 * @param folder a run's or a trial's folder, with its {@code environment.json}
	 * @return the three, each None when not recorded
	 */
	static List<JsonNode> conditions(Path folder) {
		JsonNode environment = ResultFiles.read(folder.resolve("environment.json")).orElse(PythonValues.none());
		return CONDITIONS.stream().map((key) -> PythonValues.get(environment, key)).toList();
	}

	private static boolean sameConditions(List<JsonNode> some, List<JsonNode> others) {
		for (int index = 0; index < some.size(); index++) {
			if (!PythonValues.equal(some.get(index), others.get(index))) {
				return false;
			}
		}
		return true;
	}

	// the calibration-only trials that are not discarded, in their folders' order
	private static List<Path> trials(Path results) {
		Path folder = results.resolve("trials");
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> trials = Files.list(folder)) {
			return trials.filter(Files::isDirectory)
				.filter((trial) -> !Files.exists(trial.resolve("DISCARDED")))
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A calibration the range may be fitted on.
	 *
	 * @param experiment its experiment
	 * @param kind {@code kept} or {@code trial}
	 * @param directory its folder
	 * @param source its folder under {@code results/}
	 * @param calibratedAt when it was calibrated
	 * @param calibration its {@code calibration.json}, None when there is none
	 */
	record Source(String experiment, String kind, Path directory, String source, JsonNode calibratedAt,
			JsonNode calibration) {

		static Source of(Path results, String experiment, String kind, Path directory) {
			JsonNode calibration = ResultFiles.read(directory.resolve("calibration.json")).orElse(PythonValues.none());
			String source = StreamSupport.stream(results.relativize(directory).spliterator(), false)
				.map(Path::toString)
				.collect(Collectors.joining("/"));
			return new Source(experiment, kind, directory, source, PythonValues.get(calibration, "at"), calibration);
		}

	}

}
