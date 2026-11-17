package io.archsim.engine.parity;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.sizing.Sizing;

/**
 * Where the Java departs from the frozen engine on a Kafka broker's size (engine 1.5.0,
 * ADR-0085), and how it is read as the frozen engine's all the same, guided by the frozen
 * engine's output of the same call. The Java sizes a Kafka broker from what ArchSim's
 * Kafka experiment measured ({@link Sizing#KAFKA_FIXED_CORES} and
 * {@link Sizing#KAFKA_CPU_MS_PER_MESSAGE} a message, a
 * {@link Sizing#KAFKA_WORKING_SET_GIB} working set), where Python took its messages a
 * second over an illustrative throughput per vCPU. A broker's need (in a part's size, or
 * a bare map of needs) reads as Python's once its busy cores at the peak and at the base
 * load are shown to be the measured broker's at the rates Python's imply, its CPU need
 * those over the target, and its memory the working set with Python's share for the
 * operating system. Only those figures, and the words on its CPU and memory, are then
 * Python's; its storage, specs, type and price are compared as they are.
 */
public final class KafkaSizingDepartures {

	private static final String NEED = "need";

	private static final String BUSY = "busyCores";

	private static final String BASE_BUSY = "baseBusyCores";

	private static final String CPU = "cpuCores";

	private static final String MEMORY = "memoryGib";

	/** The figures of a need the measured sizing changes. */
	private static final List<String> FIGURES = List.of(BUSY, BASE_BUSY, CPU, MEMORY);

	/** The specs whose words the measured sizing changes. */
	private static final List<String> SPECS = List.of(CPU, MEMORY);

	/** The words a part's size gives on its specs. */
	private static final List<String> WORDS = List.of("needText", "why");

	/** The map of each part's CPU need an auto-sized design comes with. */
	private static final String CPU_NEEDS = "needs";

	/**
	 * How far the Java's figures may be from the ones worked out of Python's rounding.
	 */
	private static final double ROUNDING = 1e-3;

	private static final double MS_PER_SECOND = 1000;

	private KafkaSizingDepartures() {
	}

	/**
	 * The Java's output with its Kafka brokers' sizes read as the frozen engine's.
	 * @param java the Java's output, which is not changed
	 * @param python the frozen engine's output of the same call
	 * @return a copy of the Java's output
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python) {
		JsonNode copy = java.deepCopy();
		put(copy, python);
		return copy;
	}

	private static void put(JsonNode java, JsonNode python) {
		if (java instanceof ObjectNode object && python.isObject()) {
			if (object.path(NEED) instanceof ObjectNode need && isMeasuredBroker(need, python.path(NEED))) {
				putAll(need, python.path(NEED), FIGURES);
				WORDS.stream()
					.filter((key) -> object.path(key) instanceof ObjectNode)
					.forEach((key) -> putAll((ObjectNode) object.path(key), python.path(key), SPECS));
				return;
			}
			if (isMeasuredBroker(object, python)) {
				putAll(object, python, FIGURES);
				return;
			}
			python.propertyNames().forEach((key) -> putProperty(object, python, key));
		}
		else if (java.isArray() && python.isArray()) {
			for (int index = 0; index < Math.min(java.size(), python.size()); index++) {
				put(java.get(index), python.get(index));
			}
		}
	}

	private static void putProperty(ObjectNode java, JsonNode python, String key) {
		if (CPU_NEEDS.equals(key) && java.path(key) instanceof ObjectNode needs) {
			putCpuNeeds(needs, python.path(key));
		}
		else if (java.has(key)) {
			put(java.get(key), python.get(key));
		}
	}

	private static void putAll(ObjectNode java, JsonNode python, List<String> keys) {
		keys.stream().filter(python::has).forEach((key) -> java.set(key, python.get(key)));
	}

	// a map of each part's CPU need, in vCPU: a measured broker's at the rate Python's
	// implies reads as Python's
	private static void putCpuNeeds(ObjectNode java, JsonNode python) {
		python.propertyNames().forEach((id) -> {
			double rate = python.path(id).asDouble(-1) * Sizing.BROKER_MSGS_PER_VCPU;
			double need = measuredBusy(rate) / Sizing.TARGET_CPU;
			if (rate >= 0 && isNear(java.path(id).asDouble(-1), need)) {
				java.set(id, python.get(id));
			}
		});
	}

	// a measured Kafka broker's need: its busy cores at the rates Python's imply, its CPU
	// need those over the target, its memory the working set with Python's operating
	// system's share
	private static boolean isMeasuredBroker(JsonNode java, JsonNode python) {
		if (!java.path(BUSY).isNumber() || !python.path(BUSY).isNumber()) {
			return false;
		}
		double busy = java.path(BUSY).doubleValue();
		boolean peak = isNear(busy, measuredBusy(rate(python.path(BUSY))));
		boolean cpu = isNear(java.path(CPU).asDouble(-1), busy / Sizing.TARGET_CPU);
		boolean base = !python.has(BASE_BUSY)
				|| isNear(java.path(BASE_BUSY).asDouble(-1), measuredBusy(rate(python.path(BASE_BUSY))));
		double os = python.path(MEMORY).asDouble(0) - Sizing.BROKER_GIB_PER_VCPU * python.path(CPU).asDouble(0);
		boolean memory = isNear(java.path(MEMORY).asDouble(-1), Sizing.KAFKA_WORKING_SET_GIB + os);
		return peak && cpu && base && memory;
	}

	// the messages a second Python's busy cores imply, at its throughput per vCPU and
	// target
	private static double rate(JsonNode pythonBusy) {
		return pythonBusy.doubleValue() / Sizing.TARGET_CPU * Sizing.BROKER_MSGS_PER_VCPU;
	}

	private static double measuredBusy(double rate) {
		return Sizing.KAFKA_FIXED_CORES + rate * Sizing.KAFKA_CPU_MS_PER_MESSAGE / MS_PER_SECOND;
	}

	private static boolean isNear(double value, double expected) {
		return Math.abs(value - expected) <= ROUNDING;
	}

}
