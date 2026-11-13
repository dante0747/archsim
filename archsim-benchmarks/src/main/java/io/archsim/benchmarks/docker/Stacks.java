package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.json.JsonMapper;

/**
 * Each stack's containers in {@code compose.yaml}, from {@code stacks.json}: the service
 * under test first (the one k6 calls and whose {@code /stats} calibration reads), and the
 * CPUs k6 runs on.
 *
 * <p>
 * k6 gets the CPUs the stack's other containers leave free, except the service's
 * hyperthread sibling (CPU 3 on the 4-core, 8-thread laptop), which stays idle: on that
 * sibling k6 cost the service 10-15% more CPU per request and raised p95 at 1,600 req/s
 * from 1.4 to 2.2 ms (envcheck, 30 Oct 2026). The held-out stacks give no container a
 * physical core shared with k6 or another container. A chain's gateway comes first: both
 * of its hops have one CPU, and the API's CPU per request is in the gateway's
 * {@code /stats} as {@code upstream}.
 */
public final class Stacks {

	private static final String RESOURCE = "stacks.json";

	private static final Stacks STANDARD = read();

	private final List<String> cacheStacks;

	private final Map<String, List<String>> containers;

	private final Map<String, String> k6Cpus;

	private Stacks(Catalog catalog) {
		this.cacheStacks = List.copyOf(catalog.cacheStacks());
		this.containers = Map.copyOf(catalog.containers());
		this.k6Cpus = Map.copyOf(catalog.k6Cpus());
	}

	/**
	 * The benchmark's stacks.
	 * @return the stacks
	 */
	public static Stacks standard() {
		return STANDARD;
	}

	/**
	 * Whether a stack puts a Redis cache in front of PostgreSQL.
	 * @param stack the stack
	 * @return whether it has a cache
	 */
	public boolean isCache(String stack) {
		return this.cacheStacks.contains(stack);
	}

	/**
	 * A stack's containers, the service under test first.
	 * @param stack the stack
	 * @return its compose services
	 */
	public List<String> containers(String stack) {
		return Objects.requireNonNull(this.containers.get(stack), stack);
	}

	/**
	 * The CPUs the runner gives k6 on a stack, unless told otherwise.
	 * @param stack the stack
	 * @return its cpuset
	 */
	public String k6Cpus(String stack) {
		return Objects.requireNonNull(this.k6Cpus.get(stack), stack);
	}

	private static Stacks read() {
		try (InputStream in = Stacks.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("missing resource " + RESOURCE);
			}
			return new Stacks(JsonMapper.builder().build().readValue(in, Catalog.class));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private record Catalog(List<String> cacheStacks, Map<String, List<String>> containers, Map<String, String> k6Cpus) {

	}

}
