package io.archsim.app.meta;

/**
 * Whether the API can serve: {@code ok} when it reaches its database, {@code degraded}
 * otherwise, so load balancers and health checks take the instance out of service.
 *
 * @param status {@code ok} or {@code degraded}
 * @param database {@code ok} or {@code unavailable}
 */
record Health(String status, String database) {

	/** The API reaches its database. */
	static final Health OK = new Health("ok", "ok");

	/** The API cannot reach its database. */
	static final Health DEGRADED = new Health("degraded", "unavailable");

}
