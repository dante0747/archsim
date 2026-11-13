package io.archsim.benchmarks.experiments;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * The harness's experiments, its service runtimes and the stacks they run on, from
 * {@code experiments.json}: the four the model was built with, then the five held-out
 * ones (registered on 30 Oct 2026, sealed until a frozen engine predicted them). The
 * cache-ttl experiment's Zipf skew is 1.1, not the 0.8 first proposed: over 1,000,000
 * keys with a 5 s TTL, 0.8 gives a hit ratio of 9% to 24% from 250 to 4,000 req/s, 1.1
 * the intended 52% to 69%, rising with load.
 */
public final class Experiments {

	private static final String RESOURCE = "experiments.json";

	private static final Experiments STANDARD = read();

	private final Map<String, Experiment> experiments;

	private final Map<String, ServiceRuntime> runtimes;

	private final Map<String, List<String>> stacks;

	private Experiments(Catalog catalog) {
		Map<String, Experiment> byId = new LinkedHashMap<>();
		catalog.experiments().forEach((experiment) -> byId.put(experiment.id(), experiment));
		Map<String, List<String>> byStack = new LinkedHashMap<>();
		catalog.stacks().forEach((stack, ids) -> byStack.put(stack, List.copyOf(ids)));
		this.experiments = Collections.unmodifiableMap(byId);
		this.runtimes = Collections.unmodifiableMap(new LinkedHashMap<>(catalog.runtimes()));
		this.stacks = Collections.unmodifiableMap(byStack);
		check();
	}

	// a catalog that names a runtime or an experiment it does not hold fails at once
	private void check() {
		for (Experiment experiment : this.experiments.values()) {
			if (!this.runtimes.containsKey(experiment.runtime())) {
				throw new IllegalStateException(experiment.id() + "'s runtime " + experiment.runtime()
						+ " is not one of " + this.runtimes.keySet());
			}
		}
		for (Map.Entry<String, List<String>> stack : this.stacks.entrySet()) {
			for (String id : stack.getValue()) {
				if (!this.experiments.containsKey(id)) {
					throw new IllegalStateException(
							"stack " + stack.getKey() + " runs " + id + ", which is no experiment");
				}
			}
		}
	}

	/**
	 * The harness's experiments.
	 * @return the experiments
	 */
	public static Experiments standard() {
		return STANDARD;
	}

	/**
	 * Every experiment, the tuned-on four first.
	 * @return the experiments
	 */
	public List<Experiment> all() {
		return List.copyOf(this.experiments.values());
	}

	/**
	 * An experiment by its name.
	 * @param id its name
	 * @return the experiment
	 * @throws IllegalArgumentException when there is none of that name
	 */
	public Experiment get(String id) {
		Experiment experiment = this.experiments.get(id);
		if (experiment == null) {
			throw new IllegalArgumentException(
					"no experiment '" + id + "' (one of " + String.join(", ", this.experiments.keySet()) + ")");
		}
		return experiment;
	}

	/**
	 * Each stack and the experiments run on it, in the order a campaign runs them.
	 * @return the experiments' names by stack
	 */
	public Map<String, List<String>> stacks() {
		return this.stacks;
	}

	/**
	 * How an experiment's service runs.
	 * @param experiment the experiment
	 * @return its runtime
	 */
	public ServiceRuntime runtime(Experiment experiment) {
		ServiceRuntime runtime = this.runtimes.get(experiment.runtime());
		if (runtime == null) {
			throw new IllegalArgumentException("no runtime " + experiment.runtime());
		}
		return runtime;
	}

	private static Experiments read() {
		try (InputStream in = Experiments.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("missing resource " + RESOURCE);
			}
			return read(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The experiments a catalog holds, as {@code experiments.json} writes them.
	 * @param in the catalog's JSON
	 * @return the experiments
	 * @throws IllegalStateException when an experiment's runtime or a stack's experiment
	 * is not in the catalog
	 */
	static Experiments read(InputStream in) {
		return new Experiments(JsonMapper.builder().build().readValue(in, Catalog.class));
	}

	private record Catalog(Map<String, ServiceRuntime> runtimes, List<Experiment> experiments,
			Map<String, List<String>> stacks) {

	}

}
