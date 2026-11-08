package io.archsim.engine.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.archsim.model.document.Fault;
import io.archsim.model.document.FaultKind;

/**
 * A run's planned faults, found by kind and target on the hot path.
 */
final class Faults {

	private static final double MS_PER_SECOND = 1000.0;

	private final Map<Key, List<Fault>> byTarget = new HashMap<>();

	Faults(List<Fault> faults) {
		for (Fault fault : faults) {
			this.byTarget.computeIfAbsent(new Key(fault.kind(), fault.target()), (ignored) -> new ArrayList<>())
				.add(fault);
		}
	}

	/**
	 * The fault of a kind active on a target, the first planned if several are.
	 * @param kind the kind
	 * @param target the part's or link's id
	 * @param nowMs the time
	 * @return the fault, empty when none is active
	 */
	Optional<Fault> active(FaultKind kind, String target, double nowMs) {
		List<Fault> faults = this.byTarget.get(new Key(kind, target));
		if (faults == null) {
			return Optional.empty();
		}
		double seconds = nowMs / MS_PER_SECOND;
		return faults.stream().filter((fault) -> fault.active(seconds)).findFirst();
	}

	private record Key(FaultKind kind, String target) {

	}

}
