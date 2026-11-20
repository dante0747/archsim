package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.engine.numeric.Median;
import io.archsim.model.text.PythonFormat;

/**
 * The service's load curve as a law of its rate (ADR-0093, {@link CurveForm#LAW}): each
 * staircase rate's cost per request fitted as a {@link CostLaw}, from the lowest rate to
 * where the service's CPUs fill, but no further than {@link CostLaw#FURTHEST} times the
 * top rate, as factors of the top rate's cost; flat above. Where no law falls with the
 * rate, the flat curve ({@link LoadCurveInput#of(JsonNode)}).
 */
final class LawCurveInput {

	/** What a law fitted on the staircase's own costs is fitted on. */
	private static final String CPU = "the staircase's CPU per request net of idle";

	/** What a law fitted for a host machine is fitted on. */
	private static final String WORK = "the staircase's work per request, its CPU per request net of idle times the "
			+ "machine's thread speed during the pass";

	private LawCurveInput() {
	}

	/**
	 * The law of the service's cost per request. On a host machine it is fitted on the
	 * work per request ({@link #workByLoad}): the host's threads slow as they fill, so a
	 * law of the measured cost would count that slowdown twice.
	 * @param passes the calibration's passes: {@code cpuByLoad}, and for a host machine
	 * the staircase and the idle pass
	 * @param cores the service's CPUs
	 * @param machine the host machine's fit; {@code null} for none
	 * @return the input; empty without a staircase, or when the curve spans no more than
	 * the engine's least change
	 */
	static Optional<ObjectNode> of(JsonNode passes, int cores, @Nullable MachineFit machine) {
		JsonNode byLoad = PythonValues.get(passes, "cpuByLoad");
		if (machine == null) {
			return law(byLoad, LoadCurveInput.perRequest(byLoad), cores, CPU);
		}
		return law(byLoad, workByLoad(passes, machine), cores, WORK);
	}

	/**
	 * Each staircase rate's work per request: each pass's CPU per request net of idle
	 * times the machine's thread speed during that pass, the median of the rate's passes.
	 * The CPU per request measured at a pass was slowed by the machine's load then.
	 * @param passes the calibration's passes: the staircase, each pass with its
	 * containers' CPU, and the idle pass
	 * @param fit the machine's fit
	 * @return each rate and its work per request in milliseconds, by rising rate; none
	 * without the idle pass, and no pass without the readings its machine's load needs
	 */
	static List<RatePoint> workByLoad(JsonNode passes, MachineFit fit) {
		List<RatePoint> work = LoadCurveInput.staircaseCpu(PythonValues.get(passes, "staircase"),
				PythonValues.get(passes, "idle"), (pass) -> MachineInputs.speedDuring(pass, fit));
		Map<Double, List<Double>> byRate = new TreeMap<>();
		work.forEach((pass) -> byRate.computeIfAbsent(pass.rate(), (rate) -> new ArrayList<>()).add(pass.value()));
		return byRate.entrySet()
			.stream()
			.map((rate) -> new RatePoint(rate.getKey(),
					Median.of(rate.getValue().stream().mapToDouble(Double::doubleValue))))
			.toList();
	}

	// the law fitted on the costs, or the flat curve where none falls
	private static Optional<ObjectNode> law(JsonNode byLoad, List<RatePoint> costs, int cores, String what) {
		List<RatePoint> measured = CostLaws.usable(costs);
		Optional<CostLaw> fitted = CostLaws.fit(measured);
		if (fitted.isEmpty()) {
			return LoadCurveInput.flat(byLoad);
		}
		CostLaw law = fitted.get();
		double top = measured.getLast().rate();
		CostLaw.End end = law.endAbove(top, cores);
		List<RatePoint> factors = law.factors(measured.getFirst().rate(), top, end.rps());
		if (!LoadCurveInput.spansMoreThanNoise(factors)) {
			return Optional.empty();
		}
		ObjectNode input = JsonNodeFactory.instance.objectNode().set("value", LoadCurveInput.table(factors));
		input.put("how",
				what + " (" + LoadCurveInput.words(measured) + " req/s), fitted as a law of the rate, " + law.describe()
						+ ", over the top rate's (" + PythonFormat.format(law.msAt(top), ".4f") + " ms); carried to "
						+ PythonFormat.format(end.rps(), ",.0f") + " req/s, " + reach(end.reach(), cores)
						+ ", then flat");
		return Optional.of(input);
	}

	// why the curve ends where it does, in words
	private static String reach(CostLaw.Reach reach, int cores) {
		return switch (reach) {
			case FURTHEST -> PythonFormat.format(CostLaw.FURTHEST, "g") + " times the top rate";
			case TOP -> "the top rate, where the service's CPUs had filled";
			case FILL -> "where the service's " + cores + ((cores == 1) ? " CPU fills" : " CPUs fill");
		};
	}

}
