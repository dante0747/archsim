package io.archsim.engine.runtime;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.model.document.EdgeSpec;

/**
 * A link during a run: its settings, its latency stream and what it counts (attempts,
 * retries, timeouts, failures).
 */
public final class EdgeRuntime {

	private final EdgeSpec spec;

	private final Sampler latency;

	private final PythonRandom random;

	private final Counter attempts;

	private final Counter retries;

	private final Counter timeouts;

	private final Counter failures;

	/**
	 * A link at the start of a run.
	 * @param spec the link
	 * @param timeline the run's timeline
	 * @param random the link's latency stream
	 */
	public EdgeRuntime(EdgeSpec spec, Timeline timeline, PythonRandom random) {
		this.spec = spec;
		this.latency = Sampler.of(spec.config().latencyMs());
		this.random = random;
		this.attempts = new Counter(timeline);
		this.retries = new Counter(timeline);
		this.timeouts = new Counter(timeline);
		this.failures = new Counter(timeline);
	}

	/**
	 * A one-way latency, drawn from the link's stream.
	 * @return milliseconds
	 */
	double sampleLatency() {
		return this.latency.sample(this.random);
	}

	public String id() {
		return this.spec.id();
	}

	public String sourceId() {
		return this.spec.source();
	}

	public String sourcePort() {
		return this.spec.sourcePort();
	}

	public String targetId() {
		return this.spec.target();
	}

	public Counter attempts() {
		return this.attempts;
	}

	public Counter retries() {
		return this.retries;
	}

	public Counter timeouts() {
		return this.timeouts;
	}

	public Counter failures() {
		return this.failures;
	}

}
