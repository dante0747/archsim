package io.archsim.engine.components.queue;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.kernel.Store;

/**
 * One shared queue whose consumers each take the next message as soon as they are free.
 */
final class SharedStore implements Holding {

	private final Store<Message> store;

	private final int consumers;

	/**
	 * An empty queue.
	 * @param simulation the run
	 * @param consumers the consumers that take from it
	 */
	SharedStore(Simulation simulation, int consumers) {
		this.store = new Store<>(simulation);
		this.consumers = consumers;
	}

	@Override
	public void add(Message message, double now) {
		this.store.put(message);
	}

	@Override
	public void startConsumers(Consuming consuming) {
		for (int consumer = 0; consumer < this.consumers; consumer++) {
			consuming.simulation().start((finish) -> takeNext(consuming));
		}
	}

	// a consumer takes the next message, processes it, then takes the next, forever
	private void takeNext(Consuming consuming) {
		Store.Get<Message> next = this.store.get();
		next.then(() -> consuming.deliveries().process(next.value(), () -> takeNext(consuming)));
	}

}
