package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import io.archsim.benchmarks.provenance.MachineName;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.model.text.PythonText;

/**
 * Whether the benchmark designs run on a host machine, and which (run.py's
 * {@code machine_fit}).
 *
 * <p>
 * Since ADR-0095 the designs run on the host machine this machine's own contention checks
 * fit (those naming it, {@link MachineName}) unless {@code BENCH_HOST=off}, which puts
 * them on none, as before; another value is refused. {@code BENCH_HOST_FIT} names a JSON
 * file with a fit's keys ({@link MachineFit}) to use instead of the contention checks,
 * for what-ifs; {@code BENCH_HOST_CLOCK} (0 to 1) replaces the fitted all-core clock. A
 * held-out experiment runs only on the fit its freeze names ({@code Seals}).
 */
public final class HostSwitch {

	/** The variable that switches the host machine off. */
	public static final String VARIABLE = "BENCH_HOST";

	private static final String FIT = VARIABLE + "_FIT";

	private static final String CLOCK = VARIABLE + "_CLOCK";

	private static final String ON = "on";

	private static final String OFF = "off";

	private HostSwitch() {
	}

	/**
	 * The machine the benchmark designs run on: from its own latest contention check with
	 * a probe and its latest quiet one with a service part, or from the fit
	 * {@code BENCH_HOST_FIT} names.
	 * @param environment the harness's variables, which may name the machine
	 * ({@code BENCH_MACHINE})
	 * @param results the results folder, whose {@code envcheck} holds the checks
	 * @return the machine's fit; empty when switched off, or without a quiet check of
	 * this machine
	 */
	public static Optional<MachineFit> fit(Map<String, String> environment, Path results) {
		if (!isOn(environment)) {
			return Optional.empty();
		}
		String given = environment.getOrDefault(FIT, "");
		Optional<MachineFit> fit = given.isEmpty() ? ContentionChecks.latestFit(results, MachineName.of(environment))
				: ResultFiles.read(Path.of(given)).map(MachineFit::of);
		String clock = environment.getOrDefault(CLOCK, "");
		return clock.isEmpty() ? fit : fit.map((fitted) -> fitted.withClock(clock));
	}

	/**
	 * Whether the variables leave the designs on a host machine.
	 * @param environment the harness's variables
	 * @return whether {@code BENCH_HOST} is anything but {@code off}, in any case
	 */
	public static boolean isOn(Map<String, String> environment) {
		return !OFF.equals(environment.getOrDefault(VARIABLE, "").toLowerCase(Locale.ROOT));
	}

	/**
	 * Why the variables cannot say whether the designs have a host machine.
	 * @param environment the harness's variables
	 * @return why, when {@code BENCH_HOST} is set to neither {@code on} nor {@code off}
	 * (in any case); empty otherwise
	 */
	public static Optional<String> unknownValue(Map<String, String> environment) {
		String value = environment.getOrDefault(VARIABLE, "");
		if (Set.of("", ON, OFF).contains(value.toLowerCase(Locale.ROOT))) {
			return Optional.empty();
		}
		return Optional.of(VARIABLE + "=" + PythonText.repr(value) + ": on or off, unset for on (ADR-0095)");
	}

	/**
	 * The variables that put the designs on a what-if host machine, which the repository
	 * cannot give again.
	 * @param environment the harness's variables
	 * @return {@code BENCH_HOST_FIT}, {@code BENCH_HOST_CLOCK} or both; empty for neither
	 */
	public static Optional<String> whatIf(Map<String, String> environment) {
		List<String> set = Stream.of(FIT, CLOCK)
			.filter((name) -> !environment.getOrDefault(name, "").isEmpty())
			.toList();
		return set.isEmpty() ? Optional.empty() : Optional.of(String.join(" and ", set));
	}

}
