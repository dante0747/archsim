package io.archsim.engine.text;

/**
 * The words on a queue's consumers that the design review and a run's findings share
 * (ADR-0085).
 */
public final class QueueText {

	/** Why the consumers beyond a queue's partitions sit idle. */
	public static final String ONE_CONSUMER_PER_PARTITION = "one consumer per partition is active";

	/** The advice on idle consumers. */
	public static final String IDLE_CONSUMERS_ADVICE = "Add partitions or reduce consumers.";

	private QueueText() {
	}

}
