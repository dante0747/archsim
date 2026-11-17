package io.archsim.engine.runtime;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.document.RequestType;

/**
 * A service's calls for one request, made one after another in the links' order. The
 * first failure fails the request, unless its link carries on after failures.
 *
 * <p>
 * Cache-aside: a read skips the links set to read on a cache miss until a lookup on a
 * cache answered a miss (a cache with no miss link of its own) or failed on a link that
 * carries on. It then reads those links in turn and, once every read succeeded, writes
 * the value back to that cache; a failed write-back does not fail the read. The data is
 * read once per request. A failed lookup with no such link fails the read: there is
 * nowhere else to get the data.
 */
final class Dependencies {

	private final SimContext context;

	private final List<EdgeRuntime> links;

	private final Caller caller;

	private final Consumer<Outcome> done;

	/** The links a read takes only after a cache lookup missed. */
	private final List<EdgeRuntime> onMiss;

	private boolean fetched;

	/**
	 * A service's calls for one request.
	 * @param context the run
	 * @param links the links the request takes, in order
	 * @param caller the request and the calling part
	 * @param done what to do with the outcome
	 */
	Dependencies(SimContext context, List<EdgeRuntime> links, Caller caller, Consumer<Outcome> done) {
		this.context = context;
		this.links = links;
		this.caller = caller;
		this.done = done;
		this.onMiss = reads() ? links.stream().filter((edge) -> edge.config().readsOnMiss()).toList() : List.of();
	}

	/**
	 * Calls the links from one on.
	 * @param index the first link to call
	 */
	void from(int index) {
		if (index == this.links.size()) {
			this.done.accept(Outcome.OK);
			return;
		}
		EdgeRuntime edge = this.links.get(index);
		if (reads() && edge.config().readsOnMiss()) {
			from(index + 1);
			return;
		}
		call(edge, (outcome) -> answered(index, edge, outcome));
	}

	private void answered(int index, EdgeRuntime edge, Outcome outcome) {
		if (outcome.ok() && !outcome.missed()) {
			from(index + 1);
			return;
		}
		if (!outcome.ok() && !edge.config().continueOnFailure()) {
			this.done.accept(outcome);
			return;
		}
		if (!reads() || !isCache(edge) || this.fetched) {
			// a failure this link tolerates, or the data was already read
			from(index + 1);
			return;
		}
		if (this.onMiss.isEmpty()) {
			// nowhere to read the data from (validation keeps a miss from getting here)
			this.done.accept(outcome);
			return;
		}
		fetch(0, true, edge, () -> from(index + 1));
	}

	/**
	 * Reads the links set to read on a miss in turn, then writes the value back to the
	 * cache that missed.
	 * @param index the next link to read
	 * @param allRead whether every read so far succeeded
	 * @param cache the link to the cache that missed
	 * @param next what to do after the write-back
	 */
	private void fetch(int index, boolean allRead, EdgeRuntime cache, Runnable next) {
		if (index == this.onMiss.size()) {
			this.fetched = true;
			if (allRead) {
				writeBack(cache, next);
			}
			else {
				next.run();
			}
			return;
		}
		EdgeRuntime edge = this.onMiss.get(index);
		call(edge, (result) -> {
			if (!result.ok() && !edge.config().continueOnFailure()) {
				this.done.accept(result);
				return;
			}
			fetch(index + 1, allRead && result.ok(), cache, next);
		});
	}

	// the value written back to the cache, when it takes write-backs
	private void writeBack(EdgeRuntime cache, Runnable next) {
		Optional<Handler> handler = this.context.component(cache.targetId()).writeBack();
		if (handler.isEmpty()) {
			next.run();
			return;
		}
		this.context.calls()
			.call(Calls.Call.writeBack(cache, handler.get(), this.caller.id()), this.caller.request(),
					(ignored) -> next.run());
	}

	private void call(EdgeRuntime edge, Consumer<Outcome> then) {
		this.context.calls().call(edge, this.caller.request(), this.caller.id(), then);
	}

	private boolean reads() {
		return this.caller.request().operation() == RequestType.READ;
	}

	private boolean isCache(EdgeRuntime edge) {
		return CacheSpec.TYPE.equals(this.context.component(edge.targetId()).node().type());
	}

	/**
	 * The request a part calls its links for.
	 *
	 * @param request the request
	 * @param id the calling part
	 */
	record Caller(Request request, String id) {
	}

}
