package io.archsim.engine.calibration.mapping;

import java.util.Arrays;
import java.util.Optional;

import io.archsim.model.codec.JsonEnum;

/**
 * The unit of a memory or data size a query gives.
 */
public enum MemoryUnit implements JsonEnum {

	/** Bytes. */
	BYTES("bytes"),

	/** Kibibytes, 2^10 bytes. */
	KIB("KiB"),

	/** Mebibytes, 2^20 bytes. */
	MIB("MiB"),

	/** Gibibytes, 2^30 bytes. */
	GIB("GiB");

	private static final double BYTES_PER_KIB = 1024.0;

	private final String json;

	MemoryUnit(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * Bytes per unit.
	 * @return the bytes in one unit
	 */
	public double bytes() {
		return switch (this) {
			case BYTES -> 1.0;
			case KIB -> BYTES_PER_KIB;
			case MIB -> BYTES_PER_KIB * BYTES_PER_KIB;
			case GIB -> BYTES_PER_KIB * BYTES_PER_KIB * BYTES_PER_KIB;
		};
	}

	/**
	 * The unit a setting's unit names.
	 * @param name a unit's name, such as {@code MiB}
	 * @return the unit; empty for a unit that is not one of memory
	 */
	public static Optional<MemoryUnit> named(String name) {
		return Arrays.stream(values()).filter((unit) -> unit.json.equals(name)).findFirst();
	}

}
