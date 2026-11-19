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
	 * @param siblingSpeed the share of a core each vCPU counts for in its CPU need, as a
	 * hyperthread; {@code null} on whole cores
	 * @return a line per spec it has a need for; {@code null} when there is none
	 */
	static @Nullable Map<String, String> of(Map<String, Double> need, Map<String, SpecValue> specs, String holder,
			@Nullable Double siblingSpeed) {
		Map<String, String> texts = new LinkedHashMap<>();
		for (Spec spec : Spec.values()) {
			SpecValue value = specs.get(spec.json());
			String has = (value != null) ? has(spec, value, holder) : null;
			String text = (has != null) ? line(spec, need, has, siblingSpeed) : null;
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
	 * @param has what its instance has, after a middle dot
	 * @param siblingSpeed the share of a core each vCPU counts for, as a hyperthread;
	 * {@code null} on whole cores
	 * @return the line; {@code null} when the part has no need for the spec
	 */
	private static @Nullable String line(Spec spec, Map<String, Double> need, String has,
			@Nullable Double siblingSpeed) {
		return switch (spec) {
			case CPU_CORES -> cpu(need, has, siblingSpeed);
			case MEMORY_GIB -> memory(need, has);
			case STORAGE_GIB -> {
				Double storage = need.get(Need.STORAGE_GIB.json());
				yield (storage != null) ? "stores " + SizingText.gib(storage) + has : null;
			}
		};
	}

	private static @Nullable String cpu(Map<String, Double> need, String has, @Nullable Double siblingSpeed) {
		Double cpu = need.get(Need.CPU_CORES.json());
		if (cpu == null) {
			return null;
		}
		Double busy = need.get(Need.BUSY_CORES.json());
		String why = (busy != null) ? " (" + SizingText.brief(busy) + " busy at the peak, " + target(siblingSpeed) + ")"
				: "";
		return "needs " + SizingText.brief(cpu) + " vCPU" + why + has;
	}

	/**
	 * The target a CPU need was worked out for: of what its vCPUs can do, with what each
	 * counts for when they are hyperthreads, as its size says.
	 * @param siblingSpeed the share of a core each vCPU counts for, as a hyperthread;
	 * {@code null} on whole cores
	 * @return such as "at a 60% target of what its hyperthreads can do, 0.6 of a core
	 * each"
	 */
	private static String target(@Nullable Double siblingSpeed) {
		String target = "at a " + SizingText.percent(Sizing.TARGET_CPU) + " target";
		if (siblingSpeed != null) {
			return target + " of what its hyperthreads can do, " + SizingText.brief(siblingSpeed) + " of a core each";
		}
		return target;
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
