package io.archsim.engine.cost;

import java.util.Objects;

import io.archsim.model.pricing.PriceSnapshot;

/** ArchSim's illustrative on-demand rates, from the generic price snapshot. */
final class GenericRates {

	/** The price of a vCPU-hour, roughly a large cloud's general-purpose VMs. */
	static final double VCPU_HOUR = rate("vcpuHour");

	/** The price of a GiB-hour of memory. */
	static final double GIB_HOUR = rate("gibHour");

	/** The price of a GiB-month of block storage. */
	static final double STORAGE_GIB_MONTH = rate("storageGibMonth");

	private GenericRates() {
	}

	private static double rate(String name) {
		return Objects.requireNonNull(PriceSnapshot.GENERIC.rates().get(name), name);
	}

}
