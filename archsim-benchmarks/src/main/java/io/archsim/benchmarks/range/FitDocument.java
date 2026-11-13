package io.archsim.benchmarks.range;

import java.util.HashSet;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonText;

/**
 * The parts of {@code benchmarks/knee-range.json} besides the fit itself: what names it,
 * the check of its coverage, and each checked run.
 */
final class FitDocument {

	/** How the fit was made, in its own words. */
	static final String HOW = "Fitted on the in-sample experiments only. Variation: each input's log standard "
			+ "deviation between calibrations of the same experiment (kept runs from protocol v5 on and "
			+ "calibration-only trials, under the latest kept run's conditions), pooled over experiments, and one "
			+ "common correlation between inputs. Model error: the log standard deviation of measured over predicted "
			+ "knee in the counted kept runs, by maximum likelihood after each run's calibration spread (its "
			+ "sensitivity to each input times that input's variation). The knees and inputs are today's harness and "
			+ "engine on each run's calibration (the development checks), so the model error is in-sample: fitted on "
			+ "runs the model was tuned on.";

	private static final int DECIMALS = 4;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private FitDocument() {
	}

	/**
	 * What names a fit, and how it was made.
	 * @param stamp the engine's label, the provenance and the clock
	 * @param label the label the inputs and knees came from
	 * @param runs the runs it was fitted on
	 * @return the fit's first fields
	 */
	static ObjectNode header(CheckStamp stamp, String label, List<CountedRun> runs) {
		ObjectNode header = NODES.objectNode().put("at", Timestamps.now(stamp.clock()));
		header.put("engineVersion", stamp.label());
		header.put("inputsAndKneesFrom", label);
		header.set("commit", PythonValues.get(stamp.provenance(), "commit"));
		header.set("provenance", stamp.provenance().deepCopy());
		header.put("how", HOW);
		header.put("nominalCoverage", KneeRanges.COVERAGE);
		header.put("step", Sensitivity.STEP);
		header.putArray("sensitivitySimulatedS").add(Sensitivity.WINDOW.warmup()).add(Sensitivity.WINDOW.measured());
		header.put("runs", runs.size());
		header.put("experiments", new HashSet<>(runs.stream().map(CountedRun::experiment).toList()).size());
		return header;
	}

	/**
	 * The check of the range's coverage, and how the runs were predicted before (protocol
	 * v5: the replicate sets' knees).
	 * @param check the check
	 * @param runs the runs checked
	 * @return {@code folds}, {@code counts} and {@code before}
	 */
	static ObjectNode check(RangeCheck.Result check, List<CountedRun> runs) {
		ObjectNode out = NODES.objectNode();
		ObjectNode folds = out.putObject("folds");
		RangeCheck.FOLDS.forEach(folds::put);
		out.set("counts", check.counts());
		List<JsonNode> before = runs.stream().map(CountedRun::before).filter(PythonText::truthy).toList();
		ObjectNode earlier = out.putObject("before");
		earlier.put("inRange",
				before.stream().filter((range) -> PythonText.truthy(range.path("containsMeasured"))).count());
		earlier.put("withRange", before.size());
		earlier.set("nominalCoverage",
				before.isEmpty() ? PythonValues.none() : PythonValues.get(before.getLast(), "nominalCoverage"));
		return out;
	}

	/**
	 * Each checked run: its knees, sensitivity and error, its calibration spread in the
	 * fit, and its ranges at the nominal coverage, fold by fold.
	 * @param runs the runs
	 * @param fit the fit
	 * @param check the check
	 * @return the runs, in order
	 */
	static ArrayNode checked(List<CountedRun> runs, ObjectNode fit, RangeCheck.Result check) {
		ArrayNode checked = NODES.arrayNode();
		for (int index = 0; index < runs.size(); index++) {
			CountedRun run = runs.get(index);
			ObjectNode entry = checked.addObject().put("experiment", run.experiment()).put("source", run.source());
			entry.set("calibratedAt", run.calibratedAt());
			entry.set("measuredKnee", run.measuredKnee());
			entry.set("knee", run.knee());
			entry.set("sensitivity", run.sensitivity());
			entry.put("error", PythonRound.round(run.error(), DECIMALS));
			entry.put("calibrationSigma",
					PythonRound.round(KneeRanges.calibrationSigma(run.sensitivity(), fit), DECIMALS));
			entry.set("ranges", check.detail().get(index));
		}
		return checked;
	}

}
