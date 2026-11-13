package io.archsim.benchmarks.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonRound;

/**
 * One container's CPU and memory over a measured window, from its readings: the busy
 * cores (its CPU time over the wall time between the first and the last reading), the
 * busiest interval between two readings, and its highest working set and anonymous
 * memory.
 */
public final class WindowResources {

	private static final int CORES_DECIMALS = 4;

	private static final double MICROSECONDS = 1e6;

	private static final double MIB = 1 << 20;

	private WindowResources() {
	}

	/**
	 * A container's CPU and memory from its readings.
	 * @param readings its readings, in time order
	 * @return {@code cores}, {@code maxCores}, {@code workingSetMiB} and {@code anonMiB},
	 * each when the readings give it; empty when they give none
	 */
	public static Optional<ObjectNode> of(List<CgroupReading> readings) {
		ObjectNode resources = JsonNodeFactory.instance.objectNode();
		List<CgroupReading> cpu = readings.stream().filter((reading) -> reading.usageUsec() != null).toList();
		List<Double> rates = new ArrayList<>();
		for (int index = 1; index < cpu.size(); index++) {
			if (cpu.get(index).seconds() > cpu.get(index - 1).seconds()) {
				rates.add(cores(cpu.get(index - 1), cpu.get(index)));
			}
		}
		if (!rates.isEmpty()) {
			resources.put("cores", PythonRound.round(cores(cpu.getFirst(), cpu.getLast()), CORES_DECIMALS));
			resources.put("maxCores",
					PythonRound.round(rates.stream().reduce(Math::max).orElseThrow(), CORES_DECIMALS));
		}
		highest(readings.stream().map(CgroupReading::workingSet).filter(Objects::nonNull).toList())
			.ifPresent((bytes) -> resources.put("workingSetMiB", PythonRound.round(bytes / MIB, 1)));
		highest(readings.stream().map(CgroupReading::anon).filter(Objects::nonNull).toList())
			.ifPresent((bytes) -> resources.put("anonMiB", PythonRound.round(bytes / MIB, 1)));
		return resources.isEmpty() ? Optional.empty() : Optional.of(resources);
	}

	// CPU seconds over wall seconds between two readings
	private static double cores(CgroupReading first, CgroupReading last) {
		long used = Objects.requireNonNull(last.usageUsec()) - Objects.requireNonNull(first.usageUsec());
		return used / MICROSECONDS / (last.seconds() - first.seconds());
	}

	private static Optional<Long> highest(List<Long> bytes) {
		return bytes.stream().reduce(Math::max);
	}

}
