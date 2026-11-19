package io.archsim.benchmarks.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BiFunction;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.inputs.CpuByLoad;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * Calibrates an experiment (protocol v7, whose calibration is v6's): after settling and a
 * rest, a staircase of its traffic from 50 req/s ({@link Staircase}), down again, and the
 * top rate once more: every rate twice, the top three times. Each top pass, with the
 * auxiliary mixes run right after it at the same rate, gives one replicate set of model
 * inputs (CPU per request net of idle, timings from the pass); the inputs are their
 * medians. A top pass still disturbed after its retry gives no replicate: another runs in
 * its place, two at most.
 */
public final class Calibration {

	/** The calibration protocol this runs (docs/validation.md, Protocol). */
	static final String PROTOCOL = "v7";

	/** The staircase's first rate. */
	static final int FIRST_RPS = 50;

	/** Passes at the staircase's top rate: up, down, up again. */
	static final int REPLICATES = 3;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final CalibrationPasses passes;

	private final Staircase staircase;

	private final BiFunction<Experiment, JsonNode, ObjectNode> replicateInputs;

	private final HarnessLog log;

	/**
	 * A calibration.
	 * @param passes what runs each pass on the stack
	 * @param staircase the staircase's rule for the service's CPUs
	 * @param replicateInputs the model inputs of one replicate set, anchored at its top
	 * pass ({@link ModelInputs#anchored})
	 * @param log the log, whose clock dates the calibration
	 */
	Calibration(CalibrationPasses passes, Staircase staircase,
			BiFunction<Experiment, JsonNode, ObjectNode> replicateInputs, HarnessLog log) {
		this.passes = passes;
		this.staircase = staircase;
		this.replicateInputs = replicateInputs;
		this.log = log;
	}

	/**
	 * Calibrates an experiment on its running stack.
	 * @param experiment the experiment
	 * @return its {@code calibration.json}
	 */
	public ObjectNode calibrate(Experiment experiment) {
		JsonNode settled = this.passes.settle(experiment);
		JsonNode idle = this.passes.idle(experiment);
		Climb climb = new Climb(experiment, idle);
		climb.up();
		climb.down();
		climb.replaceDisturbed();
		return climb.record(settled);
	}

	/**
	 * The staircase's passes as they run, and the replicate sets at its top.
	 */
	private final class Climb {

		private final Experiment experiment;

		private final JsonNode idle;

		private final List<JsonNode> rates = new ArrayList<>();

		/** Every pass of the staircase, in order, each with its label; each set's too. */
		private final ArrayNode runs = NODES.arrayNode();

		private final List<ObjectNode> sets = new ArrayList<>();

		private final ArrayNode discarded = NODES.arrayNode();

		Climb(Experiment experiment, JsonNode idle) {
			this.experiment = experiment;
			this.idle = idle;
		}

		// 50 req/s, then each rise the pass before allows, five at most
		void up() {
			JsonNode rate = PythonValues.integer(FIRST_RPS);
			while (rate != null) {
				this.rates.add(rate);
				String label = "up " + this.rates.size();
				JsonNode entry = steady(mainMix(), rate, label);
				this.runs.add(labelled(entry, label));
				OptionalDouble next = (this.rates.size() <= Staircase.MAX_RISES)
						? Calibration.this.staircase.nextRise(this.experiment, entry) : OptionalDouble.empty();
				rate = next.isPresent() ? PythonValues.number(next.getAsDouble()) : null;
			}
			this.sets.add(topSet(this.runs.get(this.runs.size() - 1), 1));
		}

		// every rate again, the top first
		void down() {
			for (JsonNode rate : this.rates.reversed()) {
				String label = "down " + (indexOf(rate) + 1);
				JsonNode entry = steady(mainMix(), rate, label);
				this.runs.add(labelled(entry, label));
				if (PythonValues.equal(rate, top())) {
					this.sets.add(topSet(entry, this.sets.size() + 1));
				}
			}
		}

		// another top pass for each still disturbed after its retry, two at most
		void replaceDisturbed() {
			while (clean().size() < REPLICATES && this.sets.size() < REPLICATES + 2) {
				String label = "top " + (this.sets.size() + 1);
				JsonNode entry = steady(mainMix(), top(), label);
				this.runs.add(labelled(entry, label));
				this.sets.add(topSet(entry, this.sets.size() + 1));
			}
		}

		ObjectNode record(JsonNode settled) {
			List<ObjectNode> used = (clean().size() >= 2) ? clean() : this.sets;
			ArrayNode byLoad = CpuByLoad.of(this.runs.valueStream().toList(), this.idle);
			List<ObjectNode> inputs = used.stream().map((set) -> {
				ObjectNode withLoad = set.deepCopy();
				withLoad.set("cpuByLoad", byLoad);
				return Calibration.this.replicateInputs.apply(this.experiment, withLoad);
			}).toList();
			ObjectNode calibration = NODES.objectNode().put("experiment", this.experiment.id());
			calibration.put("at", Timestamps.now(Calibration.this.log.clock()));
			calibration.put("protocol", PROTOCOL);
			calibration.put("method", "staircase up and down, CPU per request net of idle at the top rate, median of "
					+ used.size() + " replicates, anchored at the top rate, settled first (" + PROTOCOL + ")");
			calibration.set("settle", settled);
			calibration.set("idle", this.idle);
			calibration.set("staircaseRule", Staircase.rule());
			calibration.putArray("staircaseRps").addAll(this.rates);
			calibration.put("capacityBoundRps",
					PythonRound.round(Calibration.this.staircase.capacityBound(this.experiment, this.runs.get(0)), 1));
			calibration.set("passes", used.getFirst());
			calibration.set("staircase", this.runs);
			calibration.set("replicates", replicates(used));
			calibration.set("disturbedReplicates", disturbed(used));
			calibration.set("discardedPasses", this.discarded);
			calibration.set("cpuByLoad", byLoad);
			calibration.putArray("inputReplicates").addAll(inputs);
			calibration.set("inputs", ModelInputs.median(inputs));
			return calibration;
		}

		// a pass, run once more when it was disturbed, both kept in the record
		private JsonNode steady(String mix, JsonNode rate, String label) {
			JsonNode entry = Calibration.this.passes.run(this.experiment, mix, rate, label);
			if (!Disturbance.isDisturbed(entry)) {
				return entry;
			}
			JsonNode k6 = entry.get("k6");
			Calibration.this.log.line(this.experiment.id() + ": " + label + " pass disturbed (p95 "
					+ PythonFormat.format(k6.get("p95").doubleValue(), ".1f") + " ms, p50 "
					+ PythonFormat.format(k6.get("p50").doubleValue(), ".2f") + " ms); running it again");
			ObjectNode kept = labelled(entry, label);
			kept.put("reason", "p95 above " + PythonFormat.format(Disturbance.TAIL, "g") + "x the median");
			this.discarded.add(kept);
			return Calibration.this.passes.run(this.experiment, mix, rate, label);
		}

		// a top pass with the auxiliary mixes run right after it, the staircase's first
		// pass, the idle pass and the whole staircase (as it grows)
		private ObjectNode topSet(JsonNode entry, int replicate) {
			ObjectNode set = NODES.objectNode();
			set.set("low", this.runs.get(0));
			set.set("high", entry);
			set.set("idle", this.idle);
			set.set("staircase", this.runs);
			List<String> auxiliary = this.experiment.calibration().subList(1, this.experiment.calibration().size());
			for (String mix : auxiliary) {
				set.set(mix, steady(mix, top(), mix.split(":", -1)[0] + " " + replicate));
			}
			return set;
		}

		private List<ObjectNode> clean() {
			return this.sets.stream().filter((set) -> !Disturbance.isDisturbed(set.get("high"))).toList();
		}

		// each set used, without what every set shares
		private ArrayNode replicates(List<ObjectNode> used) {
			ArrayNode replicates = NODES.arrayNode();
			for (ObjectNode set : used) {
				ObjectNode replicate = set.deepCopy();
				replicate.remove(List.of("low", "idle", "staircase"));
				replicates.add(replicate);
			}
			return replicates;
		}

		// the top passes still disturbed after their retry, kept for the record only
		private ArrayNode disturbed(List<ObjectNode> used) {
			ArrayNode disturbed = NODES.arrayNode();
			this.sets.stream()
				.filter((set) -> used.stream().noneMatch((kept) -> PythonValues.equal(kept, set)))
				.forEach((set) -> disturbed.add(set.get("high")));
			return disturbed;
		}

		private String mainMix() {
			return this.experiment.calibration().getFirst();
		}

		private JsonNode top() {
			return this.rates.getLast();
		}

		private int indexOf(JsonNode rate) {
			for (int index = 0; index < this.rates.size(); index++) {
				if (PythonValues.equal(this.rates.get(index), rate)) {
					return index;
				}
			}
			throw new IllegalArgumentException("not a staircase rate: " + rate);
		}

		private static ObjectNode labelled(JsonNode entry, String label) {
			ObjectNode labelled = ((ObjectNode) entry).deepCopy();
			labelled.put("label", label);
			return labelled;
		}

	}

}
