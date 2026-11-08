package io.archsim.engine.kernel;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * An unbounded queue of items that getters wait on, as SimPy's {@code Store} serves them:
 * a put adds its item at once, but a waiting getter is served only when the put is
 * processed, or when a new get is made meanwhile; each serving looks at the oldest
 * waiting getter alone.
 *
 * @param <T> the items' type
 */
public final class Store<T> {

	private final Simulation simulation;

	private final Deque<T> items = new ArrayDeque<>();

	private final Deque<Get<T>> getting = new ArrayDeque<>();

	/**
	 * An empty store.
	 * @param simulation the simulation it belongs to
	 */
	public Store(Simulation simulation) {
		this.simulation = simulation;
	}

	/**
	 * Adds an item.
	 * @param item the item
	 * @return the put, triggered at once; when it is processed, the oldest waiting getter
	 * may be served
	 */
	public Event put(T item) {
		Signal put = new Signal(this.simulation);
		put.addCallback((event) -> serveOldest());
		this.items.addLast(item);
		put.succeed();
		return put;
	}

	/**
	 * Takes the oldest item, or waits for one.
	 * @return the get, which happens with the item
	 */
	public Get<T> get() {
		Get<T> get = new Get<>(this.simulation);
		this.getting.addLast(get);
		serveOldest();
		return get;
	}

	/**
	 * How many items wait to be taken.
	 * @return the items
	 */
	public int size() {
		return this.items.size();
	}

	private void serveOldest() {
		if (this.getting.isEmpty() || this.items.isEmpty()) {
			return;
		}
		this.getting.removeFirst().succeedWith(this.items.removeFirst());
	}

	/**
	 * Taking an item from a {@link Store}: it happens with the item.
	 *
	 * @param <T> the item's type
	 */
	public static final class Get<T> extends ValueEvent<T> {

		private Get(Simulation simulation) {
			super(simulation);
		}

	}

}
