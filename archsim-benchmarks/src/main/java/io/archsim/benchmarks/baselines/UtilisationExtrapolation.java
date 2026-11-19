package io.archsim.benchmarks.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.inputs.CpuByLoad;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The knee by utilisation extrapolation: each container's measured CPU in the calibration
 * staircase's passes against the offered rate, a straight line through them, and the load
 * at which the line reaches the container's CPUs. It reads no model input and no measured
 * load step. It is not a Universal Scalability Law fit, which needs throughput against
 * concurrency up to near saturation: a staircase of open-loop passes at low load cannot
 * identify one.
 */
final class UtilisationExtrapolation {

	/** How the knee is found, in the file's words. */
	static final String HOW = "Each container's CPU in the calibration staircase's passes (busy cores, net of what "
			+ "it burns at rest) against the offered rate, fitted with a straight line by least squares and "
			+ "extrapolated to the container's CPUs less its CPU at rest. The knee is the lowest such load among "
			+ "the containers a request waits for; one that works behind a queue is listed but does not bound it. "
			+ "A linear extrapolation of utilisation, not a Universal Scalability Law fit, which a staircase of "
			+ "open-loop passes at low load cannot identify. No model input, no simulation, no measured load step.";

	/** Why a calibration from before the staircase has no extrapolation. */
	static final String NO_STAIRCASE = "the calibration has no staircase: protocols before v5 calibrated without one";

	/** Why no container bounds the knee. */
	static final String NO_LINE = "no container a request waits for has a line that rises with the rate";

	/** Why a container has no capacity. */
	static final String NO_RISE = "its CPU did not rise with the rate";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String WHY = "why";

	private UtilisationExtrapolation() {
	}

	/**
	 * The knee a calibration's staircase extrapolates to.
	 * @param calibration the run's {@code calibration.json}
	 * @param containers the CPUs each container could use, and the ones behind a queue
	 * @return how it was found, {@code kneeRps} and the container it is {@code limitedBy}
	 * (None with {@code why} when there is no staircase, or no line to extrapolate), the
	 * staircase's {@code ratesRps}, where the CPUs come from and each container's line
	 * @throws IllegalStateException when the staircase read a container whose CPUs are
	 * not known
	 */
	static ObjectNode of(JsonNode calibration, ContainerCpus containers) {
		List<JsonNode> passes = PythonValues.get(calibration, "staircase").valueStream().toList();
		if (passes.isEmpty()) {
			ObjectNode none = NODES.objectNode().put("how", HOW);
			none.set(Baselines.KNEE, PythonValues.none());
			return none.put(WHY, NO_STAIRCASE);
		}
		List<Line> lines = lines(passes, idle(calibration), containers);
		ObjectNode extrapolation = knee(lines);
		extrapolation.set("ratesRps", rates(passes));
		extrapolation.put("cpusFrom", containers.source());
		ArrayNode list = extrapolation.putArray("containers");
		lines.forEach((line) -> list.add(line.json()));
		return extrapolation;
	}

	// each container's line, in the order the first pass names them
	private static List<Line> lines(List<JsonNode> passes, JsonNode idle, ContainerCpus containers) {
		return passes.getFirst()
			.get("containerCpuMs")
			.propertyNames()
			.stream()
			.map((container) -> Line.of(container, passes, idle, containers))
			.toList();
	}

	// the lowest load at which the line of a container a request waits for reaches its
	// CPUs
	private static ObjectNode knee(List<Line> lines) {
		Optional<Line> lowest = lines.stream()
			.filter((line) -> line.onRequestPath() && line.capacityRps().isPresent())
			.min(Comparator.comparingDouble((line) -> line.capacityRps().orElseThrow()));
		ObjectNode knee = NODES.objectNode().put("how", HOW);
		knee.set(Baselines.KNEE,
				lowest.map((line) -> rate(line.capacityRps().orElseThrow())).orElse(PythonValues.none()));
		if (lowest.isEmpty()) {
			knee.put(WHY, NO_LINE);
		}
		return knee.put("limitedBy", lowest.map(Line::container).orElse(null));
	}

	// the idle pass, among the passes since protocol v5
	private static JsonNode idle(JsonNode calibration) {
		JsonNode amongPasses = calibration.path("passes").path("idle");
		return amongPasses.isObject() ? amongPasses : calibration.path("idle");
	}

	// the staircase's rates, each once, lowest first, as the passes record them
	private static ArrayNode rates(List<JsonNode> passes) {
		List<JsonNode> rates = new ArrayList<>();
		for (JsonNode pass : passes) {
			JsonNode rate = pass.get("rateRps");
			if (rates.stream().noneMatch((seen) -> PythonValues.equal(seen, rate))) {
				rates.add(rate);
			}
		}
		rates.sort(PythonValues::compare);
		return NODES.arrayNode().addAll(rates);
	}

	private static JsonNode rate(double requestsPerSecond) {
		return PythonValues.number(Units.rate(requestsPerSecond));
	}

	/**
	 * A container's line through the staircase.
	 *
	 * @param container the container
	 * @param cpus the CPUs it could use
	 * @param onRequestPath whether a request waits for it
	 * @param passes the passes that read its CPU
	 * @param idleCores the cores it keeps busy at rest
	 * @param fitted its busy cores, net of those, against the rate; {@code null} when its
	 * passes are at one rate
	 */
	private record Line(String container, int cpus, boolean onRequestPath, int passes, double idleCores,
			@Nullable StraightLine fitted) {

		static Line of(String container, List<JsonNode> passes, JsonNode idle, ContainerCpus containers) {
			Integer cpus = containers.cpus().get(container);
			if (cpus == null) {
				throw new IllegalStateException("no CPUs known for the container " + container);
			}
			List<StraightLine.Point> points = new ArrayList<>();
			for (JsonNode pass : passes) {
				OptionalDouble cores = CpuByLoad.netCores(pass, idle, container);
				if (cores.isPresent()) {
					points.add(new StraightLine.Point(pass.get("rateRps").doubleValue(), cores.getAsDouble()));
				}
			}
			JsonNode atRest = idle.path("containerCpuMsPerSecond").path(container);
			return new Line(container, cpus, !containers.behindQueue().contains(container), points.size(),
					PythonValues.isNone(atRest) ? 0.0 : atRest.doubleValue() / Units.MS_PER_SECOND,
					StraightLine.through(points).orElse(null));
		}

		// where the line reaches the container's CPUs less its CPU at rest
		OptionalDouble capacityRps() {
			if (this.fitted == null || this.fitted.slope() <= 0) {
				return OptionalDouble.empty();
			}
			return OptionalDouble.of(this.fitted.reaches(this.cpus - this.idleCores));
		}

		ObjectNode json() {
			ObjectNode json = NODES.objectNode().put("container", this.container).put("cpus", this.cpus);
			json.put("onRequestPath", this.onRequestPath).put("passes", this.passes);
			json.put("idleCores", Units.precise(this.idleCores));
			if (this.fitted != null) {
				json.put("interceptCores", Units.precise(this.fitted.intercept()))
					.put("cpuMsPerRequest", Units.precise(this.fitted.slope() * Units.MS_PER_SECOND))
					.put("rSquared", Units.precise(this.fitted.rSquared()));
			}
			OptionalDouble capacity = capacityRps();
			json.set("capacityRps", capacity.isPresent() ? rate(capacity.getAsDouble()) : PythonValues.none());
			if (capacity.isEmpty()) {
				json.put(WHY, NO_RISE);
			}
			return json;
		}

	}

}
