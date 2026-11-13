package io.archsim.benchmarks.machine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonStatistics;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.host.HostMachine;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The machine's thread model from a contention check's probe and a quiet check's service
 * part (run.py's {@code host_fit}):
 * <ul>
 * <li>physical cores: the distinct cores of the VM's topology;</li>
 * <li>all-core clock: Windows' actual frequency against the machine's busy logical
 * processors, a line through every probe condition, at all the threads busy over
 * one;</li>
 * <li>sibling speed: the probe's CPU per unit of work against the busy threads, given
 * that clock (the engine's {@code Machine.speed}), least squares in log over a grid;</li>
 * <li>background: the machine's busy threads with the stack up and at rest;</li>
 * <li>k6's CPU per request: its container's busy cores over the rate, the median over the
 * as-benchmarked steps below 90% of the service's core.</li>
 * </ul>
 */
public final class ContentionFit {

	/**
	 * A check counts only when the machine at rest (the stack up and idle) had fewer busy
	 * threads than this: one taken with other work running measures that.
	 */
	static final double QUIET_THREADS = 1.0;

	/**
	 * Probe conditions with the machine's logical processors all busy are left out of the
	 * sibling fit: past them the demand is not seen (the processors take turns).
	 */
	static final double FIT_MOST_BUSY = 7.9;

	private static final int FEWEST_CONDITIONS = 4;

	private static final double LOWEST_CLOCK = 0.3;

	/** The sibling speeds tried, in hundredths. */
	private static final int SLOWEST_SIBLING = 20;

	private static final int FASTEST_SIBLING = 100;

	private static final double HUNDREDTHS = 100.0;

	/** k6's steps count only while the service used less than this share of its core. */
	private static final double SERVICE_CORE_SHARE = 0.9;

	private static final double MS_PER_S = 1000.0;

	private static final int CLOCK_DECIMALS = 3;

	private static final int K6_DECIMALS = 4;

	private ContentionFit() {
	}

	/**
	 * The fit of one check, its probe and its service part.
	 * @param check the check
	 * @return the fit; empty when the machine was not quiet at rest, or a reading is
	 * missing
	 */
	public static Optional<MachineFit> of(JsonNode check) {
		return fit(check, check, null);
	}

	/**
	 * The fit of a check's probe and another check's service part.
	 * @param check the check whose probe is fitted
	 * @param service the check whose service part (the machine at rest, k6's CPU) is
	 * taken
	 * @return the fit; empty when the machine was not quiet at rest, or a reading is
	 * missing
	 */
	public static Optional<MachineFit> of(JsonNode check, JsonNode service) {
		return fit(check, service, " (service part " + at(service) + ")");
	}

	private static Optional<MachineFit> fit(JsonNode check, JsonNode service, @Nullable String servicePart) {
		JsonNode background = service.path("serviceAtRest").path("busyLogicalProcessors");
		if (!background.isNumber() || background.doubleValue() >= QUIET_THREADS) {
			return Optional.empty();
		}
		JsonNode siblings = check.path("topology").path("siblings");
		Set<JsonNode> cores = new HashSet<>(siblings.valueStream().toList());
		List<Condition> conditions = conditions(check);
		List<Double> k6 = k6(service);
		if (cores.isEmpty() || conditions.size() < FEWEST_CONDITIONS || k6.isEmpty()) {
			return Optional.empty();
		}
		Machine machine = new Machine(cores.size(), siblings.size(), Clock.of(conditions, siblings.size()));
		List<Condition> fitted = conditions.stream().filter((condition) -> condition.busy() < FIT_MOST_BUSY).toList();
		Sibling sibling = Sibling.of(fitted, machine);
		double k6Ms = PythonValues.median(k6.stream().map(PythonValues::number).toList()).doubleValue();
		String how = "contention check " + at(check) + ((servicePart != null) ? servicePart : "") + ": "
				+ machine.describe(conditions.size()) + "; " + sibling.describe(fitted.size()) + "; "
				+ PythonFormat.format(background.doubleValue(), ".2f") + " threads busy at rest; k6 "
				+ PythonFormat.format(k6Ms, ".4f") + " ms of CPU per request (median of " + k6.size() + " steps)";
		return Optional.of(new MachineFit(machine.cores(), machine.threadsPerCore(), sibling.speed(),
				PythonRound.round(machine.clock().share(), CLOCK_DECIMALS),
				PythonRound.round(background.doubleValue(), CLOCK_DECIMALS), PythonRound.round(k6Ms, K6_DECIMALS),
				how));
	}

	// every probe condition with the machine's busy threads and frequency
	private static List<Condition> conditions(JsonNode check) {
		List<Condition> conditions = new ArrayList<>();
		for (JsonNode round : check.path("probeRounds").valueStream().toList()) {
			round.valueStream()
				.filter((condition) -> PythonText.truthy(condition.path("probe"))
						&& PythonText.truthy(condition.path("host"))
						&& PythonText.truthy(condition.path("host").path("frequencyMHz")))
				.forEach((condition) -> conditions
					.add(new Condition(condition.at("/host/busyLogicalProcessors").doubleValue(),
							condition.at("/host/frequencyMHz").doubleValue(),
							condition.at("/probe/cpuUsPerUnit").doubleValue())));
		}
		return conditions;
	}

	// k6's CPU per request at each as-benchmarked step below 90% of the service's core
	private static List<Double> k6(JsonNode service) {
		return service.path("service")
			.valueStream()
			.filter((step) -> "as benchmarked".equals(step.path("condition").asString())
					&& PythonText.truthy(step.path("k6Cores")) && serviceCores(step) < SERVICE_CORE_SHARE)
			.map((step) -> step.get("k6Cores").doubleValue() * MS_PER_S / step.get("rateRps").doubleValue())
			.toList();
	}

	// the service's busy cores at a step, a whole core when not read
	private static double serviceCores(JsonNode step) {
		JsonNode cores = step.path("serviceCores");
		return PythonText.truthy(cores) ? cores.doubleValue() : 1.0;
	}

	private static String at(JsonNode check) {
		return PythonText.str(PythonValues.get(check, "at"));
	}

	/**
	 * A probe condition.
	 *
	 * @param busy the machine's busy logical processors
	 * @param megahertz Windows' actual frequency
	 * @param cost the probe's CPU per unit of work, in microseconds
	 */
	private record Condition(double busy, double megahertz, double cost) {

	}

	/**
	 * The all-core clock: a line through the conditions' frequencies against their busy
	 * threads, at all the threads over one.
	 *
	 * @param oneThread the frequency with one busy thread
	 * @param allThreads the frequency with every thread busy
	 */
	private record Clock(double oneThread, double allThreads) {

		static Clock of(List<Condition> conditions, int threads) {
			double meanBusy = PythonStatistics.fmean(conditions.stream().map(Condition::busy).toList());
			double meanMegahertz = PythonStatistics.fmean(conditions.stream().map(Condition::megahertz).toList());
			// added as CPython 3.12's sum() adds floats
			double covariance = PythonValues.sumOfFloats(conditions.stream()
				.map((condition) -> (condition.busy() - meanBusy) * (condition.megahertz() - meanMegahertz))
				.toList());
			double variance = PythonValues.sumOfFloats(conditions.stream()
				.map((condition) -> (condition.busy() - meanBusy) * (condition.busy() - meanBusy))
				.toList());
			double slope = covariance / variance;
			return new Clock(meanMegahertz + slope * (1 - meanBusy), meanMegahertz + slope * (threads - meanBusy));
		}

		double share() {
			return Math.min(1.0, Math.max(LOWEST_CLOCK, this.allThreads / this.oneThread));
		}

	}

	/**
	 * The machine's topology and clock.
	 *
	 * @param cores its physical cores
	 * @param threads its logical processors
	 * @param clock its all-core clock
	 */
	private record Machine(int cores, int threads, Clock clock) {

		int threadsPerCore() {
			return this.threads / this.cores;
		}

		// the engine's machine with a thread sharing its core at a speed,
		// and nothing else on it
		HostMachine at(double siblingSpeed) {
			return new HostMachine(this.cores, threadsPerCore(), siblingSpeed, this.clock.share(), 0, 0);
		}

		String describe(int conditions) {
			return this.cores + " physical cores of " + this.threads + " threads (the VM's topology); clock "
					+ PythonFormat.format(this.clock.oneThread(), ",.0f") + " MHz with 1 busy thread, "
					+ PythonFormat.format(this.clock.allThreads(), ",.0f") + " with " + this.threads
					+ " (a line through " + conditions + " probe conditions) = "
					+ PythonFormat.format(this.clock.share(), ".3f");
		}

	}

	/**
	 * The sibling speed whose machine fits the probe best: the least squared error of the
	 * log of its CPU per unit at full speed.
	 *
	 * @param error the squared error
	 * @param speed the speed of a thread sharing its core
	 */
	private record Sibling(double error, double speed) {

		static Sibling of(List<Condition> conditions, Machine machine) {
			Sibling best = new Sibling(Double.POSITIVE_INFINITY, 0);
			for (int hundredths = SLOWEST_SIBLING; hundredths <= FASTEST_SIBLING; hundredths++) {
				double speed = hundredths / HUNDREDTHS;
				HostMachine candidate = machine.at(speed);
				List<Double> logs = conditions.stream()
					.map((condition) -> StrictMath.log(condition.cost() * candidate.speed(condition.busy())))
					.toList();
				double middle = PythonStatistics.fmean(logs);
				double error = PythonValues
					.sumOfFloats(logs.stream().map((log) -> (log - middle) * (log - middle)).toList());
				if (error < best.error()) {
					best = new Sibling(error, speed);
				}
			}
			return best;
		}

		String describe(int conditions) {
			return "a thread sharing its core at " + PythonFormat.format(this.speed, ".2f")
					+ " of its speed alone (the probe's CPU per unit of work against the busy threads, " + conditions
					+ " conditions, rms " + PythonFormat.format(StrictMath.sqrt(this.error / conditions), ".1%")
					+ " in log)";
		}

	}

}
