package io.archsim.benchmarks.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;

/**
 * What the Java engine reproduces of the frozen engine's predictions: of the kept
 * calibrations either engine re-simulated, how many it gave exactly the frozen engine's
 * knee, with the one sentence the validation page and {@code /accuracy} show of it
 * (in-sample, never in the headline). The page's older runs were predicted by older
 * engines, so the sentence speaks of the latest predictions only.
 */
final class JavaEngineCheck {

	/**
	 * The frozen engine the Java engine is checked against, which predicted every latest
	 * run.
	 */
	static final String FROZEN_ENGINE = "0.16.0";

	/**
	 * The development checks paired: every kept calibration re-simulated by the same
	 * harness on the frozen engine, and on the Java engine, which wrote its folder before
	 * it was engine 1.0.0 and replays its runs unchanged.
	 */
	private static final List<String> FOLDERS = List.of(FROZEN_ENGINE + "-port", FROZEN_ENGINE + "-java");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private JavaEngineCheck() {
	}

	/**
	 * The check, from the development checks under {@code results/dev/}.
	 * @param results the results folder
	 * @param engines the engines, which name the Java engine's version
	 * @return the check; empty while neither engine has re-simulated any calibration
	 */
	static Optional<ObjectNode> of(Path results, Engines engines) {
		List<Path> folders = FOLDERS.stream().map((label) -> results.resolve("dev").resolve(label)).toList();
		Set<String> names = new TreeSet<>();
		folders.forEach((folder) -> names.addAll(checks(folder)));
		if (names.isEmpty()) {
			return Optional.empty();
		}
		int same = (int) names.stream()
			.filter((name) -> sameKnee(folders.getFirst().resolve(name), folders.getLast().resolve(name)))
			.count();
		String version = javaVersion(engines);
		String text = (same == names.size())
				? "Re-simulated, in-sample: engine " + version + " (Java) re-simulates every kept calibration with "
						+ "exactly " + FROZEN_ENGINE + "'s knees (" + same + " of " + names.size()
						+ "); the latest predictions shown are " + FROZEN_ENGINE + "'s, which " + version
						+ " reproduces."
				: "Re-simulated, in-sample: engine " + version + " (Java) re-simulates " + same + " of " + names.size()
						+ " kept calibrations with exactly " + FROZEN_ENGINE + "'s knees; the latest predictions shown "
						+ "are " + FROZEN_ENGINE + "'s.";
		ObjectNode check = NODES.objectNode().put("version", version).put("reproduces", FROZEN_ENGINE);
		return Optional.of(check.put("sameKnees", same).put("keptCalibrations", names.size()).put("text", text));
	}

	/**
	 * The validation page's sentence on the Java engine, linked to its parity report.
	 * @param results the results folder
	 * @param engines the engines
	 * @return the sentence
	 */
	static String block(Path results, Engines engines) {
		return of(results, engines).map((check) -> {
			String text = check.get("text").stringValue();
			return text.substring(0, text.length() - 1) + " ([java-parity.md](validation/java-parity.md)).";
		}).orElse("_Not re-simulated yet._");
	}

	// whether both engines re-simulated a calibration and found the same knee, or none
	private static boolean sameKnee(Path frozen, Path java) {
		Optional<JsonNode> frozenCheck = ResultFiles.read(frozen);
		Optional<JsonNode> javaCheck = ResultFiles.read(java);
		if (frozenCheck.isEmpty() || javaCheck.isEmpty()) {
			return false;
		}
		JsonNode frozenKnee = knee(frozenCheck.get());
		JsonNode javaKnee = knee(javaCheck.get());
		if (PythonValues.isNone(frozenKnee) || PythonValues.isNone(javaKnee)) {
			return PythonValues.isNone(frozenKnee) && PythonValues.isNone(javaKnee);
		}
		return PythonValues.compare(frozenKnee, javaKnee) == 0;
	}

	private static JsonNode knee(JsonNode check) {
		return PythonValues.get(check.get("comparison").get("predictedKnee"), "kneeRps");
	}

	// the latest Java engine engines.json lists
	private static String javaVersion(Engines engines) {
		List<JsonNode> entries = engines.entries();
		for (int i = entries.size() - 1; i >= 0; i--) {
			if ("java".equals(PythonValues.get(entries.get(i), "engine").stringValue(""))) {
				return entries.get(i).get("version").stringValue();
			}
		}
		throw new IllegalStateException("engines.json lists no Java engine");
	}

	// the checks under a folder, by their path in it
	private static List<String> checks(Path folder) {
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(folder)) {
			return files.filter((file) -> file.getFileName().toString().endsWith(".json"))
				.map((file) -> folder.relativize(file).toString().replace('\\', '/'))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
