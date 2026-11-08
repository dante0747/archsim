package io.archsim.engine.reporting;

import java.util.LinkedHashMap;
import java.util.SequencedMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What limits a part, as the Python engine's report names it: the lowest ceiling, but a
 * resource within 3% above the slots' when the slots' is lowest.
 */
class CapacityTests {

	@Test
	void limitedBy_aResourceLowest_isThatResource() {
		String limit = Capacity.limitedBy(ceilings("cpu", 150.0), withSlots(ceilings("cpu", 150.0), 200.0));

		assertThat(limit).isEqualTo("cpu");
	}

	@Test
	void limitedBy_slotsLowestAndAResourceWithin3Percent_isTheResource() {
		String limit = Capacity.limitedBy(ceilings("cpu", 199.18), withSlots(ceilings("cpu", 199.18), 198.82));

		assertThat(limit).isEqualTo("cpu");
	}

	@Test
	void limitedBy_slotsLowestAndResourcesFurtherAbove_isTheSlots() {
		String limit = Capacity.limitedBy(ceilings("cpu", 207.0), withSlots(ceilings("cpu", 207.0), 200.0));

		assertThat(limit).isEqualTo("slots");
	}

	@Test
	void limitedBy_slotsLowestAndTwoResourcesWithin3Percent_isTheLowerResource() {
		SequencedMap<String, Double> limits = ceilings("cpu", 205.0);
		limits.put("memory", 201.0);

		String limit = Capacity.limitedBy(limits, withSlots(limits, 200.0));

		assertThat(limit).isEqualTo("memory");
	}

	@Test
	void limitedBy_twoCeilingsAlike_isTheFirst() {
		SequencedMap<String, Double> limits = ceilings("cpu", 180.0);
		limits.put("memory", 180.0);

		String limit = Capacity.limitedBy(limits, withSlots(limits, 200.0));

		assertThat(limit).isEqualTo("cpu");
	}

	@Test
	void limitedBy_noCeilings_isNone() {
		String limit = Capacity.limitedBy(new LinkedHashMap<>(), new LinkedHashMap<>());

		assertThat(limit).isNull();
	}

	private static SequencedMap<String, Double> ceilings(String name, double ceiling) {
		SequencedMap<String, Double> ceilings = new LinkedHashMap<>();
		ceilings.put(name, ceiling);
		return ceilings;
	}

	private static SequencedMap<String, Double> withSlots(SequencedMap<String, Double> limits, double slots) {
		SequencedMap<String, Double> ceilings = new LinkedHashMap<>(limits);
		ceilings.put("slots", slots);
		return ceilings;
	}

}
