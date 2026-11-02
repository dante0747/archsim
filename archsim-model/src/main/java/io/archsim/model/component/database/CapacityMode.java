package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** How capacity units are bought: per request, or provisioned. */
public enum CapacityMode implements JsonEnum {

	/** The table serves the on-demand units from the start, paid per request. */
	ON_DEMAND("on_demand"),
	/** The table serves the read and write units provisioned for it. */
	PROVISIONED("provisioned");

	private final String json;

	CapacityMode(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
