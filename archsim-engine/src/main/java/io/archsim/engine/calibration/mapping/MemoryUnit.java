package io.archsim.engine.calibration.mapping;

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

	private final String json;

	MemoryUnit(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
