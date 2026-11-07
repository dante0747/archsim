package io.archsim.engine.calibration.otlp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a calibration takes from spans: per service, its handler time without downstream
 * calls; per database, its read and write times; the observed call graph; and, where
 * spans record it, each service's CPU time.
 *
 * @param services each service's SERVER spans' own time, without the time their outgoing
 * calls cover, in milliseconds
 * @param dbReads each database's read calls' durations, in milliseconds
 * @param dbWrites each database's write calls' durations, in milliseconds
 * @param calls how many calls each caller made to each callee
 * @param roots the root spans' durations, in milliseconds
 * @param serverMs each service's SERVER spans' durations, in milliseconds
 * @param cpu each service's SERVER spans that record their CPU time: the CPU time and the
 * span's own time
 * @param startNs when the first span started, in nanoseconds since the epoch
 * @param endNs when the last span ended, in nanoseconds since the epoch
 * @param spans how many spans
 * @param parentLoops how many spans' chains of parent links loop back on themselves
 */
public record TraceSummary(Map<String, List<Double>> services, Map<String, List<Double>> dbReads,
		Map<String, List<Double>> dbWrites, Map<Call, Integer> calls, List<Double> roots,
		Map<String, List<Double>> serverMs, Map<String, List<CpuSample>> cpu, long startNs, long endNs, int spans,
		int parentLoops) {

	/**
	 * Copies the summaries, in the order they were found.
	 */
	public TraceSummary {
		services = copy(services);
		dbReads = copy(dbReads);
		dbWrites = copy(dbWrites);
		calls = Collections.unmodifiableMap(new LinkedHashMap<>(calls));
		roots = List.copyOf(roots);
		serverMs = copy(serverMs);
		cpu = copy(cpu);
	}

	private static <T> Map<String, List<T>> copy(Map<String, List<T>> map) {
		Map<String, List<T>> copy = new LinkedHashMap<>();
		map.forEach((key, values) -> copy.put(key, List.copyOf(values)));
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * Calls from one service to a service or a database.
	 *
	 * @param caller the calling service
	 * @param callee the service or database called
	 */
	public record Call(String caller, String callee) {

	}

	/**
	 * A SERVER span's CPU time against its own time.
	 *
	 * @param cpuMs the CPU time it records, in milliseconds
	 * @param ownMs its own time, in milliseconds
	 */
	public record CpuSample(double cpuMs, double ownMs) {

	}

}
