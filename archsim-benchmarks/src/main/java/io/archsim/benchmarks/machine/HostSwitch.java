package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.archsim.benchmarks.results.ResultFiles;

/**
 * Whether the benchmark designs run on a host machine, and which (run.py's
 * {@code machine_fit}).
 *
 * <p>
 * Only with {@code BENCH_HOST=on}: by default the designs have none, as before engine
 * 0.16.0 (the in-sample what-if did not clear the accuracy gate:
 * IMPLEMENTATION_STATUS.md, W27). {@code BENCH_HOST_FIT} names a JSON file with a fit's
 * keys ({@link MachineFit}) to use instead of the contention checks, for what-ifs;
 * {@code BENCH_HOST_CLOCK} (0 to 1) replaces the fitted all-core clock.
 */
public final class HostSwitch {

	/** The variable that switches the host machine on. */
	public static final String VARIABLE = "BENCH_HOST";

	private static final String FIT = VARIABLE + "_FIT";

	private static final String CLOCK = VARIABLE + "_CLOCK";

	private HostSwitch() {
	}

	/**
	 * The machine the benchmark designs run on: from the latest contention check with a
	 * probe and the latest quiet one with a service part, or from the fit
	 * {@code BENCH_HOST_FIT} names.
	 * @param environment the harness's variables
	 * @param results the results folder, whose {@code envcheck} holds the checks
	 * @return the machine's fit; empty unless switched on, or without a quiet check
	 */
	public static Optional<MachineFit> fit(Map<String, String> environment, Path results) {
		if (!isOn(environment)) {
			return Optional.empty();
		}
		String given = environment.getOrDefault(FIT, "");
		Optional<MachineFit> fit = given.isEmpty() ? ContentionChecks.latestFit(results)
				: ResultFiles.read(Path.of(given)).map(MachineFit::of);
		String clock = environment.getOrDefault(CLOCK, "");
		return clock.isEmpty() ? fit : fit.map((fitted) -> fitted.withClock(clock));
	}

	/**
	 * Whether the variables ask for a host machine.
	 * @param environment the harness's variables
	 * @return whether {@code BENCH_HOST} is {@code on}, in any case
	 */
	public static boolean isOn(Map<String, String> environment) {
		return "on".equals(environment.getOrDefault(VARIABLE, "").toLowerCase(Locale.ROOT));
	}

}
