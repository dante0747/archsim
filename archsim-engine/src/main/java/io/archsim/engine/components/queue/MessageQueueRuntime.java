package io.archsim.engine.components.queue;

import java.util.List;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.component.queue.Partitioning;
import io.archsim.model.document.NodeSpec;

/**
 * A message queue during a run: a producer's request succeeds once its publish is
 * acknowledged ({@link Publishing}); consumers take the messages in the background, from
 * partitions or a shared queue, and call the queue's links ({@link Deliveries}). What
 * happens to a message later never changes how its producer's request ended. Its report
 * adds its backlog's, consumers' and messages' figures and series ({@link QueueReport})
 * and formulas ({@link QueueExplain}).
 */
public final class MessageQueueRuntime extends RuntimeComponent {

	private final QueueState state;

	private final Publishing publishing;

	/**
	 * A queue at the start of a run, its consumers started.
	 * @param context the run
	 * @param node the part
	 * @param config its settings
	 */
	public MessageQueueRuntime(SimContext context, NodeSpec node, MessageQueueConfig config) {
		super(context, node, OptionalInt.of(config.consumerSlots()));
		this.state = QueueState.of(context, config, new QueueState.Owner(node.id(), metrics(), this::sampleTime));
		this.publishing = new Publishing(this.state, this::sampleTime,
				context.stream("node:" + node.id() + ":publish"));
	}

	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		this.publishing.publish(request, done);
	}

	/**
	 * Whether messages go to partitions by their key.
	 */
	@Override
	public boolean usesKeys() {
		MessageQueueConfig config = this.state.config();
		return config.partitioningInEffect() == Partitioning.KEY && config.partitions() > 1;
	}

	/**
	 * Whether messages wait in the backlog or a consumer is processing one.
	 */
	@Override
	public boolean backgroundBusy() {
		return this.state.backlog().depth() > 0 || this.state.deliveries().busy();
	}

	@Override
	public SequencedMap<String, @Nullable Number> extraSummary() {
		return QueueReport.extraSummary(this.state);
	}

	@Override
	public SequencedMap<String, List<@Nullable Double>> extraTimeseries() {
		return QueueReport.extraTimeseries(this.state);
	}

	@Override
	public List<Formula> explain(NodeSummary summary) {
		return QueueExplain.of(this.state, context().workload(), summary);
	}

	@Override
	public List<TechnologyTrait> behaviour() {
		return QueueReport.behaviour(this.state);
	}

}
