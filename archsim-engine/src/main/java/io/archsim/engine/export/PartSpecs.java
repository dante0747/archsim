package io.archsim.engine.export;

import java.util.Optional;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.settings.LenientSettings;

/**
 * A part's CPU, memory and storage, as the exports write them for a container: each a
 * number above zero, or none.
 *
 * @param cpuCores its CPU cores; {@code null} when it has none
 * @param memoryGib its memory, in GiB; {@code null} when it has none
 * @param storageGib its storage, in GiB; {@code null} when it has none
 */
record PartSpecs(@Nullable Double cpuCores, @Nullable Double memoryGib, @Nullable Double storageGib) {

	/** The smallest CPU a container requests, in cores. */
	private static final double SMALLEST_CPU_REQUEST = 0.01;

	/** Hundredths of a core in a core: a CPU request is rounded up to these. */
	private static final double HUNDREDTHS = 100;

	/** How far below a hundredth a request may fall and still round to it. */
	private static final double ROUNDING_SLACK = 1e-9;

	/** Millicores in a core. */
	private static final double MILLICORES = 1000;

	/** MiB in a GiB. */
	private static final double MEBIBYTES = 1024;

	/**
	 * The specs a part's settings set.
	 * @param settings the part's settings
	 * @return its specs
	 */
	static PartSpecs of(LenientSettings settings) {
		return new PartSpecs(aboveZero(settings, "cpuCores"), aboveZero(settings, "memoryGib"),
				aboveZero(settings, "storageGib"));
	}

	/**
	 * Kubernetes requests and limits: the part's CPU and memory as both, or its CPU need
	 * as the request when it needs less than its cores.
	 * @param cpuNeed the CPU an auto-sized part needs (its cores at the target
	 * utilization); {@code null} when its CPU was set
	 * @return {@code {requests, limits}}; empty when the part has neither CPU nor memory
	 */
	Optional<ObjectNode> kubernetesResources(@Nullable Double cpuNeed) {
		if (this.cpuCores == null && this.memoryGib == null) {
			return Optional.empty();
		}
		ObjectNode quantities = JsonNodeFactory.instance.objectNode();
		if (this.cpuCores != null) {
			quantities.put("cpu", kubernetesCpu(this.cpuCores));
		}
		if (this.memoryGib != null) {
			quantities.put("memory", wholeOr(this.memoryGib, "Gi", MEBIBYTES, "Mi"));
		}
		ObjectNode requests = quantities.deepCopy();
		if (this.cpuCores != null && cpuNeed != null && cpuNeed > 0 && cpuNeed < this.cpuCores) {
			double rounded = Math.ceil(cpuNeed * HUNDREDTHS - ROUNDING_SLACK) / HUNDREDTHS;
			requests.put("cpu", kubernetesCpu(Math.max(SMALLEST_CPU_REQUEST, rounded)));
		}
		ObjectNode resources = JsonNodeFactory.instance.objectNode();
		resources.set("requests", requests);
		resources.set("limits", quantities);
		return Optional.of(resources);
	}

	/**
	 * Compose limits and reservations, both the part's CPU and memory.
	 * @return {@code {limits, reservations}}; empty when the part has neither CPU nor
	 * memory
	 */
	Optional<ObjectNode> composeResources() {
		if (this.cpuCores == null && this.memoryGib == null) {
			return Optional.empty();
		}
		ObjectNode quantities = JsonNodeFactory.instance.objectNode();
		if (this.cpuCores != null) {
			quantities.put("cpus", YamlText.number(this.cpuCores));
		}
		if (this.memoryGib != null) {
			quantities.put("memory", wholeOr(this.memoryGib, "G", MEBIBYTES, "M"));
		}
		ObjectNode resources = JsonNodeFactory.instance.objectNode();
		resources.set("limits", quantities.deepCopy());
		resources.set("reservations", quantities);
		return Optional.of(resources);
	}

	/**
	 * The storage a volume claim requests: the part's storage rounded up to a whole GiB.
	 * @param otherwise the storage when the part has none, in GiB
	 * @return the quantity, such as {@code 10Gi}
	 */
	String kubernetesStorage(double otherwise) {
		double gib = (this.storageGib != null) ? this.storageGib : otherwise;
		return ExportText.whole(Math.ceil(gib)) + "Gi";
	}

	/**
	 * A CPU quantity: whole cores, or millicores.
	 * @param cores the cores
	 * @return the quantity, such as {@code 2} or {@code 500m}
	 */
	private static String kubernetesCpu(double cores) {
		return isWhole(cores) ? ExportText.whole(cores) : ExportText.rounded(cores * MILLICORES) + "m";
	}

	/**
	 * A quantity in a whole unit, or in a smaller one.
	 * @param value the quantity in the unit
	 * @param unit the unit's suffix
	 * @param smallerPerUnit how many of the smaller unit make one
	 * @param smallerUnit the smaller unit's suffix
	 * @return the quantity with its suffix
	 */
	private static String wholeOr(double value, String unit, double smallerPerUnit, String smallerUnit) {
		return isWhole(value) ? ExportText.whole(value) + unit
				: ExportText.rounded(value * smallerPerUnit) + smallerUnit;
	}

	private static boolean isWhole(double value) {
		return value == Math.rint(value);
	}

	private static @Nullable Double aboveZero(LenientSettings settings, String name) {
		OptionalDouble value = settings.number(name);
		return (value.isPresent() && value.getAsDouble() > 0) ? value.getAsDouble() : null;
	}

}
