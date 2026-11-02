package io.archsim.model.component;

import io.archsim.model.codec.JsonEnum;

/**
 * What kind of part a component type is; load balancers and queues send each call to one
 * target.
 */
public enum Category implements JsonEnum {

	/** Client groups, which generate the workload. */
	TRAFFIC("traffic"),
	/** Parts that pass requests on, such as load balancers. */
	NETWORK("network"),
	/** Parts that run application code, such as services and the clusters they run on. */
	COMPUTE("compute"),
	/** Parts that hold data: caches and databases. */
	STORAGE("storage"),
	/** Message queues and streams. */
	MESSAGING("messaging"),
	/** Third-party services a design calls but does not operate. */
	EXTERNAL("external");

	private final String json;

	Category(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
