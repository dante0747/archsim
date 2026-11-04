package io.archsim.engine.sizing;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * What a part needs against what its instance has, a line per spec, such as "needs 0.3
 * GiB (up to 0.5 when queues are full) · c6g.xlarge has 8 GiB".
 */
final class NeedText {

	/** The most memory counts as more than the typical when it is this much above it. */
	private static final double MEMORY_MARGIN = 0.005;

	private NeedText() {
	}

	/**
	 * The lines of a part's specs.
	 * @param need what it needs, by JSON name
	 * @param specs the specs it is simulated with
	 * @param holder what holds its resources: its instance type, or a pod, a replica, ...
	 * @return a line per spec it has a need for; {@code null} when there is none
	 */
	static @Nullable Map<String, String> of(Map<String, Double> need, Map<String, SpecValue> specs, String holder) {
		Map<String, String> texts = new LinkedHashMap<>();
		for (Spec spec : Spec.values()) {
			SpecValue value = specs.get(spec.json());
			String text = (value != null) ? line(spec, need, value, holder) : null;
			if (text != null) {
				texts.put(spec.json(), text);
			}
		}
		return texts.isEmpty() ? null : texts;
	}

	/**
	 * One spec's line.
	 * @param spec the spec
	 * @param need what the part needs
	 * @param value the spec it is simulated with
	 * @param holder what holds its resources
	 * @return the line; {@code null} when the part has no need for the spec
	 */
	private static @Nullable String line(Spec spec, Map<String, Double> need, SpecValue value, String holder) {
		String has = has(spec, value, holder);
		return switch (spec) {
			case CPU_CORES -> cpu(need, has);
			case MEMORY_GIB -> memory(need, has);
			case STORAGE_GIB -> {
				Double storage = need.get(Need.STORAGE_GIB.json());
				yield (storage != null) ? "stores " + SizingText.gib(storage) + has : null;
			}
		};
	}

	private static @Nullable String cpu(Map<String, Double> need, String has) {
		Double cpu = need.get(Need.CPU_CORES.json());
		if (cpu == null) {
			return null;
		}
		Double busy = need.get(Need.BUSY_CORES.json());
		String why = (busy != null) ? " (" + SizingText.brief(busy) + " busy at the peak, at a "
				+ SizingText.percent(Sizing.TARGET_CPU) + " target)" : "";
		return "needs " + SizingText.brief(cpu) + " vCPU" + why + has;
	}

	private static @Nullable String memory(Map<String, Double> need, String has) {
		Double typical = need.get(Need.MEMORY_GIB.json());
		Double most = need.get(Need.MEMORY_GIB_MAX.json());
		if (typical != null && most != null && most > typical + MEMORY_MARGIN) {
			return "needs " + SizingText.gib(typical) + " (up to " + SizingText.gib(most) + " when queues are full)"
					+ has;
		}
		Double one = (typical != null) ? typical : most;
		return (one != null) ? "needs " + SizingText.gib(one) + has : null;
	}

	/**
	 * What the instance has, after a middle dot.
	 * @param spec the spec
	 * @param value the spec it is simulated with
	 * @param holder what holds its resources
	 * @return the text; empty when the spec has no number
	 */
	private static String has(Spec spec, SpecValue value, String holder) {
		if (!value.value().isNumber()) {
			return "";
		}
		double amount = value.value().doubleValue();
		return " · " + holder + " has "
				+ ((spec == Spec.CPU_CORES) ? SizingText.plain(amount) + " vCPU" : SizingText.gib(amount));
	}

}
