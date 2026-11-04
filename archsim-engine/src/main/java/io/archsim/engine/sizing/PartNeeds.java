package io.archsim.engine.sizing;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.estimate.EstimatedPart;
import io.archsim.engine.estimate.PartEstimate;
import io.archsim.engine.numeric.PythonRound;

/** What a part needs, for its sizing summary. */
final class PartNeeds {

	/** The decimals a need keeps. */
	private static final int NEED_DIGITS = 4;

	private static final double MILLIS_PER_SECOND = 1000;

	private PartNeeds() {
	}

	/**
	 * What a part needs: its size's needs, with a service's typical memory (no more than
	 * its most) from the estimate.
	 * @param size its auto size
	 * @param estimate its estimate; {@code null} when the estimate has none
	 * @return the needs, by their JSON names
	 */
	static Map<String, Double> of(PartSize size, @Nullable PartEstimate estimate) {
		Map<String, Double> need = new LinkedHashMap<>();
		size.need().forEach((kind, value) -> need.put(kind.json(), value));
		Double typical = typicalMemory(size, estimate);
		if (typical != null) {
			Double most = need.get(Need.MEMORY_GIB_MAX.json());
			need.put(Need.MEMORY_GIB.json(), Math.min(typical, (most != null) ? most : typical));
		}
		return need;
	}

	/**
	 * A service's typical memory: its fixed memory plus the requests one replica holds on
	 * average at the peak (its arrival rate times its time in the part, Little's law).
	 * @param size its auto size
	 * @param estimate its estimate
	 * @return the memory, in GiB; {@code null} without a memory model or an estimate
	 */
	private static @Nullable Double typicalMemory(PartSize size, @Nullable PartEstimate estimate) {
		MemoryModel model = size.memoryModel();
		if (model == null || !(estimate instanceof EstimatedPart part)) {
			return null;
		}
		Double rps = part.arrivalRps();
		Double stay = part.meanResidenceMs();
		if (rps == null || stay == null) {
			return null;
		}
		double perPool = rps / Math.max(1.0, model.pools());
		double held = perPool * stay / MILLIS_PER_SECOND;
		return model.fixedGib() + held * model.perRequestGib() + perPool * model.perRpsGib();
	}

	/**
	 * Needs as the summary shows them.
	 * @param need the needs
	 * @return each rounded to four places, as Python's {@code round}
	 */
	static Map<String, Double> rounded(Map<String, Double> need) {
		Map<String, Double> rounded = new LinkedHashMap<>();
		need.forEach((kind, value) -> rounded.put(kind, PythonRound.round(value, NEED_DIGITS)));
		return rounded;
	}

}
