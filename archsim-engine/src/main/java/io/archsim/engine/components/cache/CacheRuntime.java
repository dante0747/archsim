package io.archsim.engine.components.cache;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.EdgeRuntime;
import io.archsim.engine.runtime.Handler;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;

/**
 * A cache during a run. Every command takes an executor on the key's shard, waits out a
 * snapshot's fork, and runs its time on the shard's CPU ({@link CacheCommands}). A write
 * invalidates the key. A read hits with the fixed ratio, or when its key was filled less
 * than the TTL ago; a miss is left to the caller (cache-aside, when the cache has no miss
 * link: the caller reads the data store and writes the value back, see
 * {@link #writeBack}), or read through the cache's miss link and filled with one more
 * command. Its report adds its shards' figures and series ({@link CacheReport}) and
 * formulas ({@link CacheExplain}).
 */
public final class CacheRuntime extends RuntimeComponent {

	private static final double MS_PER_SECOND = 1000.0;

	private static final String INVALIDATIONS = "invalidations";

	private static final String POPULATES = "populates";

	private final CacheConfig config;

	private final CacheShards shards;

	private final CacheUpkeep upkeep;

	private final CacheCommands commands;

	private final PythonRandom hitStream;

	/**
	 * A cache at the start of a run.
	 * @param context the run
	 * @param node the part
	 * @param config its settings
	 */
	public CacheRuntime(SimContext context, NodeSpec node, CacheConfig config) {
		super(context, node, OptionalInt.of(config.executors() * config.shards()));
		this.config = config;
		PythonRandom serviceStream = context.stream("node:" + node.id() + ":service");
		this.hitStream = context.stream("node:" + node.id() + ":hits");
		Sampler serviceTime = Sampler.of(config.serviceTimeMs());
		this.commands = new CacheCommands(context.simulation(), node.id(), metrics(),
				() -> sampleTime(serviceTime, serviceStream));
		this.shards = new CacheShards(context, config, metrics());
		this.upkeep = new CacheUpkeep(context, config, this.shards, metrics());
	}

	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		metrics().arrive(now());
		CacheShard shard = this.shards.of(request.arrival().key());
		this.commands.run(shard, request, () -> answer(request, shard, done), (reason) -> {
			countRefusedRead(request);
			reject(reason, done);
		});
	}

	/**
	 * Counts a read the cache refused because it was down as a refused lookup; a write's
	 * invalidation is no lookup.
	 */
	@Override
	public void refusedWhileDown(Request request) {
		countRefusedRead(request);
	}

	/**
	 * The write-back a cache-aside caller sends after reading the data store on a miss:
	 * one more command, which fills the key.
	 * @return the write-back's handler; none when the cache does not populate on a miss
	 */
	@Override
	public Optional<Handler> writeBack() {
		return this.config.populateOnMiss() ? Optional.of(this::populate) : Optional.empty();
	}

	@Override
	public boolean usesKeys() {
		return this.config.keysExpire() || this.config.shards() > 1;
	}

	@Override
	public void finish(double end) {
		super.finish(end);
		this.shards.finish(end);
	}

	@Override
	public SequencedMap<String, Double> resourceCapacity(OptionalDouble holdMs, OptionalDouble serviceMs) {
		return CacheReport.resourceCapacity(this.config, this.shards, metrics(), serviceMs);
	}

	@Override
	public SequencedMap<String, @Nullable Number> extraSummary() {
		return CacheReport.extraSummary(this.shards);
	}

	@Override
	public SequencedMap<String, List<@Nullable Double>> extraTimeseries() {
		return CacheReport.extraTimeseries(this.shards, this.upkeep, metrics());
	}

	@Override
	public List<Formula> explain(NodeSummary summary) {
		return CacheExplain.of(this.config, this.shards, summary);
	}

	@Override
	public List<TechnologyTrait> behaviour() {
		return CacheReport.behaviour(this.config, this.shards, this.upkeep);
	}

	// a write invalidates the key; a read looks it up
	private void answer(Request request, CacheShard shard, Consumer<Outcome> done) {
		if (request.operation() == RequestType.WRITE) {
			if (this.config.keysExpire()) {
				shard.keys().invalidate(request.arrival().key());
			}
			metrics().count(INVALIDATIONS, now());
			depart(request, Outcome.OK, done);
			return;
		}
		lookUp(request, shard, done);
	}

	private void lookUp(Request request, CacheShard shard, Consumer<Outcome> done) {
		request.removeRefusal(id()).ifPresent((refused) -> metrics().uncount(NodeMetrics.REFUSED_LOOKUPS, refused));
		double now = now();
		boolean hit = read(request, shard);
		if (hit) {
			metrics().count(NodeMetrics.HITS, now);
			request.span(id(), SpanKind.HIT, new Request.Interval(now, now), "served from the cache");
			depart(request, Outcome.OK, done);
			return;
		}
		metrics().count(NodeMetrics.MISSES, now);
		List<EdgeRuntime> missLinks = outEdges("miss");
		if (missLinks.isEmpty()) {
			request.span(id(), SpanKind.MISS, new Request.Interval(now, now),
					"not cached: the caller reads the data store");
			depart(request, Outcome.MISS, done);
			return;
		}
		request.span(id(), SpanKind.MISS, new Request.Interval(now, now), "not cached: asking the data store");
		context().calls()
			.call(missLinks.getFirst(), request, id(), (outcome) -> readThrough(request, shard, outcome, done));
	}

	/**
	 * Reads the request's key: with the TTL model it hits when the shard holds it
	 * unexpired (and becomes the most recently used); with a fixed ratio a hit is drawn.
	 * @param request the read
	 * @param shard its key's shard
	 * @return {@code true} for a hit
	 */
	private boolean read(Request request, CacheShard shard) {
		if (this.config.keysExpire()) {
			return shard.keys().read(request.arrival().key(), now());
		}
		return this.hitStream.nextDouble() < this.config.hitRatio();
	}

	// after the data store answered a read through the miss link: one more command fills
	// the key, unless the cache's queue refuses it
	private void readThrough(Request request, CacheShard shard, Outcome outcome, Consumer<Outcome> done) {
		if (!outcome.ok() || !this.config.populateOnMiss()) {
			depart(request, outcome, done);
			return;
		}
		this.commands.run(shard, request, () -> {
			boolean filled = fill(request, shard);
			if (filled) {
				metrics().count(POPULATES, now());
			}
			depart(request, outcome, done);
		}, (reason) -> depart(request, outcome, done));
	}

	private void populate(Request request, Consumer<Outcome> done) {
		double start = now();
		metrics().arrive(start);
		CacheShard shard = this.shards.of(request.arrival().key());
		this.commands.run(shard, request, () -> {
			boolean filled = fill(request, shard);
			if (filled) {
				metrics().count(POPULATES, now());
				request.span(id(), SpanKind.POPULATE, new Request.Interval(start, now()),
						"value written back to the cache");
			}
			depart(request, Outcome.OK, done);
		}, (reason) -> reject(reason, done));
	}

	/**
	 * Fills the request's key, until it expires after the TTL.
	 * @param request the request
	 * @param shard its key's shard
	 * @return whether the shard took the key: with a fixed ratio, always
	 */
	private boolean fill(Request request, CacheShard shard) {
		if (!this.config.keysExpire()) {
			return true;
		}
		double now = now();
		return shard.keys().fill(request.arrival().key(), now, now + this.config.ttlSeconds() * MS_PER_SECOND);
	}

	// a read counts once, at its first refusal, however often its caller retries it; the
	// count is taken back when the cache answers a later attempt (ADR-0082)
	private void countRefusedRead(Request request) {
		double now = now();
		if (request.operation() == RequestType.READ && request.addRefusal(id(), now)) {
			metrics().count(NodeMetrics.REFUSED_LOOKUPS, now);
		}
	}

	private void reject(FailureReason reason, Consumer<Outcome> done) {
		metrics().reject(reason, now());
		done.accept(Outcome.rejected(reason, id()));
	}

	private void depart(Request request, Outcome outcome, Consumer<Outcome> done) {
		metrics().depart(now(), outcome, request);
		done.accept(outcome);
	}

}
