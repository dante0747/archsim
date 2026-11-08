package io.archsim.engine.kernel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A number of slots (threads, connections, a lock) that requests hold one each, granted
 * in the order they were asked for, as SimPy's {@code Resource} grants them. A release
 * frees its slot at once, but waiting requests are granted only when the release is
 * processed, or when a new request is made meanwhile: each grant looks at the oldest
 * waiting request alone.
 */
public final class Resource {

	private final Simulation simulation;

	private final int capacity;

	private final List<Request> users = new ArrayList<>();

	private final Deque<Request> waiting = new ArrayDeque<>();

	/**
	 * A resource with free slots.
	 * @param simulation the simulation it belongs to
	 * @param capacity how many slots, at least 1
	 * @throws IllegalArgumentException for fewer than one slot
	 */
	public Resource(Simulation simulation, int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("\"capacity\" must be > 0.");
		}
		this.simulation = simulation;
		this.capacity = capacity;
	}

	/**
	 * Asks for a slot.
	 * @return the request, triggered at once when a slot is free and nothing waits
	 */
	public Request request() {
		Request request = new Request(this);
		this.waiting.addLast(request);
		grantOldest();
		return request;
	}

	/**
	 * Gives a slot back: the slot is free at once, and the release, triggered at once,
	 * grants the oldest waiting request (if any still waits) when it is processed.
	 * @param request the request that held it
	 * @return the release
	 */
	public Event release(Request request) {
		Signal release = new Signal(this.simulation);
		release.addCallback((event) -> grantOldest());
		this.users.remove(request);
		release.succeed();
		return release;
	}

	/**
	 * How many slots are held.
	 * @return the requests granted and not released
	 */
	public int count() {
		return this.users.size();
	}

	/**
	 * How many requests wait for a slot.
	 * @return the requests not granted and not cancelled
	 */
	public int queued() {
		return this.waiting.size();
	}

	/**
	 * How many slots there are.
	 * @return the capacity
	 */
	public int capacity() {
		return this.capacity;
	}

	private void grantOldest() {
		if (this.waiting.isEmpty() || this.users.size() >= this.capacity) {
			return;
		}
		Request oldest = this.waiting.removeFirst();
		this.users.add(oldest);
		oldest.trigger();
	}

	/**
	 * A request for a slot of a {@link Resource}: it happens when the slot is granted.
	 */
	public static final class Request extends Event {

		private final Resource resource;

		private Request(Resource resource) {
			super(resource.simulation);
			this.resource = resource;
		}

		/**
		 * Stops waiting for a slot: a request not granted yet leaves the queue; a granted
		 * one is unchanged and must be released.
		 */
		public void cancel() {
			if (!triggered()) {
				this.resource.waiting.remove(this);
			}
		}

	}

}
