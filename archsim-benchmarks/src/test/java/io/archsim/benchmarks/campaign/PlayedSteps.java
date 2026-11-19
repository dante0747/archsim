package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.results.ResultFiles;

/**
 * pg-mixed's latest run played back as any experiment's steps: its predictions and its
 * measured steps, a step meeting the objective or failing it as the script says (every
 * step, the ladder's top alone, or the steps up to a knee); each call kept, with the
 * predictions' digest and the seals {@code measured.json} held as each step started.
 */
final class PlayedSteps implements ExperimentSteps {

	private static final Path LATEST = RepositoryLayout.current().results().resolve("pg-mixed");

	private static final JsonNode PREDICTED = ResultFiles.read(LATEST.resolve("predictions.json")).orElseThrow();

	private static final JsonNode MEASURED = ResultFiles.read(LATEST.resolve("measured.json")).orElseThrow();

	private final Path results;

	private final Script script;

	private final List<String> calls;

	private final List<String> digests = new ArrayList<>();

	private final List<JsonNode> sealsHeld = new ArrayList<>();

	/**
	 * The steps.
	 * @param results the results folder the run writes to
	 * @param script what the steps play
	 * @param calls where each call is kept ({@code calibrate pg-mixed},
	 * {@code measure 250})
	 */
	PlayedSteps(Path results, Script script, List<String> calls) {
		this.results = results;
		this.script = script;
		this.calls = calls;
	}

	@Override
	public ObjectNode calibrate(Experiment experiment) {
		this.calls.add("calibrate " + experiment.id());
		if (experiment.id().equals(this.script.failing())) {
			throw new IllegalStateException(experiment.id() + "'s calibration failed");
		}
		return JsonText.object("{\"inputs\": {}}");
	}

	@Override
	public ObjectNode predict(Experiment experiment, JsonNode calibration) {
		ObjectNode predictions = (ObjectNode) PREDICTED.deepCopy();
		ArrayNode steps = (ArrayNode) predictions.get("steps");
		if (this.script.top() > 0) {
			((ObjectNode) steps.get(steps.size() - 1)).put("offeredRps", this.script.top());
		}
		return predictions;
	}

	@Override
	public ObjectNode measure(Experiment experiment, long rps) {
		this.calls.add("measure " + rps);
		Path folder = this.results.resolve(experiment.id());
		this.digests.add(sha256(folder.resolve("predictions.json")));
		this.sealsHeld.add(ResultFiles.read(folder.resolve("measured.json"))
			.map((measured) -> measured.path("extensionSeals").deepCopy())
			.orElse(JsonText.read("null")));
		JsonNode steps = MEASURED.get("steps");
		JsonNode played = meets(rps) ? steps.get(0) : steps.get(steps.size() - 1);
		return ((ObjectNode) played.deepCopy()).put("offeredRps", rps);
	}

	@Override
	public ObjectNode predictStep(Experiment experiment, JsonNode inputs, long rps) {
		JsonNode steps = PREDICTED.get("steps");
		return ((ObjectNode) steps.get(steps.size() - 1).deepCopy()).put("offeredRps", rps);
	}

	@Override
	public String postgresLog() {
		return """
				LOG:  database system is ready
				LOG:  checkpoint starting: time
				LOG:  automatic vacuum of table "bench.public.items"
				""";
	}

	/**
	 * The ladder's top step, as predicted.
	 * @return its offered load
	 */
	long top() {
		JsonNode steps = PREDICTED.get("steps");
		return (this.script.top() > 0) ? this.script.top() : steps.get(steps.size() - 1).get("offeredRps").longValue();
	}

	/**
	 * The digest of {@code predictions.json} as each step started.
	 * @return the digests, in the steps' order
	 */
	List<String> digests() {
		return Collections.unmodifiableList(this.digests);
	}

	/**
	 * The {@code extensionSeals} {@code measured.json} held on disk as each step started.
	 * @return the seals, in the steps' order; a missing file's as None
	 */
	List<JsonNode> sealsHeld() {
		return Collections.unmodifiableList(this.sealsHeld);
	}

	/**
	 * The ladder's lowest step, as predicted.
	 * @return its offered load
	 */
	static long lowest() {
		return PREDICTED.get("steps").get(0).get("offeredRps").longValue();
	}

	/**
	 * The ladder's highest step, as predicted.
	 * @return its offered load
	 */
	static long highest() {
		JsonNode steps = PREDICTED.get("steps");
		return steps.get(steps.size() - 1).get("offeredRps").longValue();
	}

	// every step meets the objective, or those up to the knee, or the ladder's top alone
	private boolean meets(long rps) {
		if (this.script.everyStepMeets()) {
			return true;
		}
		return (this.script.knee() > 0) ? rps <= this.script.knee() : rps == top();
	}

	private static String sha256(Path file) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * What the steps play.
	 *
	 * @param everyStepMeets whether every step meets the objective; else the ladder's top
	 * alone does, or the steps up to the knee
	 * @param top the ladder's top step, in place of the recorded one; 0 for the recorded
	 * @param failing the experiment whose calibration fails; empty for none
	 * @param knee the highest load that meets the objective; 0 for the ladder's top alone
	 * meeting it
	 */
	record Script(boolean everyStepMeets, long top, String failing, long knee) {

		/** The recorded ladder, its top meeting the objective once. */
		static final Script TOP_MEETS = new Script(false, 0, "");

		Script(boolean everyStepMeets, long top, String failing) {
			this(everyStepMeets, top, failing, 0);
		}

		/**
		 * The recorded ladder on a stack whose knee is at a load.
		 * @param knee the highest load that meets the objective
		 * @return the script
		 */
		static Script kneeAt(long knee) {
			return new Script(false, 0, "", knee);
		}

	}

}
