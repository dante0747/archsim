package io.archsim.engine.kernel;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.KernelCases.Run;
import io.archsim.engine.kernel.KernelCases.Trace;
import io.archsim.engine.kernel.Resource.Request;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resources and stores in SimPy's order: each test runs the Java twin of a program of
 * {@code scripts/export_kernel_cases.py} (named after it) and expects the lines SimPy
 * recorded and as many events processed. Each step of a program is a method, named after
 * what the Python does there.
 */
class QueueCasesTests {

	private final Simulation simulation = new Simulation();

	private final Trace trace = new Trace(this.simulation);

	@Test
	void run_resourceContention_grantsAsSimPy() {
		Resource resource = new Resource(this.simulation, 2);
		List<User> users = List.of(new User("u0", 0, 1), new User("u1", 0, 2), new User("u2", 0, 1),
				new User("u3", 0, 0.5), new User("u4", 1, 1), new User("u5", 1.5, 0.25));
		users.forEach(
				(user) -> this.simulation.start((finish) -> arrive(user.after(), () -> ask(resource, user, finish))));

		Run run = run();

		assertThat(run).isEqualTo(KernelCases.recorded("resourceContention"));
	}

	@Test
	void run_requestGrantsOldest_grantsTheOldestWaiterFirst() {
		Resource resource = new Resource(this.simulation, 1);
		this.simulation.start((finish) -> holder(resource, finish));
		this.simulation.start((finish) -> waiter(resource, "w1", finish));
		this.simulation.start((finish) -> waiter(resource, "w2", finish));

		Run run = run();

		assertThat(run).isEqualTo(KernelCases.recorded("requestGrantsOldest"));
	}

	@Test
	void run_queueTimeouts_cancelsAsSimPy() {
		Resource resource = new Resource(this.simulation, 1);
		patientUser(resource, new User("a", 0, 2), 1);
		patientUser(resource, new User("b", 0, 1), 1);
		patientUser(resource, new User("c", 0, 1), 2);
		patientUser(resource, new User("d", 0.5, 1), 1.5);
		patientUser(resource, new User("e", 1, 1), 5);

		Run run = run();

		assertThat(run).isEqualTo(KernelCases.recorded("queueTimeouts"));
	}

	@Test
	void run_stores_serveAsSimPy() {
		Store<String> store = new Store<>(this.simulation);
		this.simulation.start((finish) -> getter(store, "g1", 0, finish));
		this.simulation.start((finish) -> getter(store, "g2", 0, finish));
		this.simulation.start((finish) -> putter(store, finish));
		this.simulation.start((finish) -> getter(store, "g3", 3, finish));

		Run run = run();

		assertThat(run).isEqualTo(KernelCases.recorded("stores"));
	}

	private void arrive(double after, Runnable next) {
		if (after == 0) {
			next.run();
		}
		else {
			this.simulation.timeout(after).then(next);
		}
	}

	private void ask(Resource resource, User user, Runnable finish) {
		Request request = resource.request();
		this.trace.log(user.name() + " asks, triggered=" + KernelCases.pythonBoolean(request.triggered()) + " count="
				+ resource.count() + " queue=" + resource.queued());
		request.then(() -> {
			this.trace.log(user.name() + " holds");
			this.simulation.timeout(user.hold()).then(() -> releaseAndCount(resource, request, user, finish));
		});
	}

	private void releaseAndCount(Resource resource, Request request, User user, Runnable finish) {
		resource.release(request);
		this.trace.log(user.name() + " releases, count=" + resource.count() + " queue=" + resource.queued());
		finish.run();
	}

	private void holder(Resource resource, Runnable finish) {
		Request request = resource.request();
		request.then(() -> {
			this.trace.log("holder holds");
			this.simulation.timeout(1).then(() -> holderAsksAgain(resource, request, finish));
		});
	}

	private void holderAsksAgain(Resource resource, Request held, Runnable finish) {
		resource.release(held);
		this.trace.log("holder released, count=" + resource.count() + " queue=" + resource.queued());
		Request late = resource.request();
		this.trace.log("holder asks again: triggered=" + KernelCases.pythonBoolean(late.triggered()) + " queue="
				+ resource.queued());
		late.then(() -> {
			this.trace.log("holder holds again");
			resource.release(late);
			finish.run();
		});
	}

	private void waiter(Resource resource, String name, Runnable finish) {
		Request request = resource.request();
		request.then(() -> {
			this.trace.log(name + " holds");
			this.simulation.timeout(0.5).then(() -> {
				resource.release(request);
				this.trace.log(name + " releases");
				finish.run();
			});
		});
	}

	private void patientUser(Resource resource, User user, double patience) {
		this.simulation.start((finish) -> arrive(user.after(), () -> askPatiently(resource, user, patience, finish)));
	}

	private void askPatiently(Resource resource, User user, double patience, Runnable finish) {
		Request request = resource.request();
		if (request.triggered()) {
			hold(resource, request, user, finish);
			return;
		}
		this.simulation.anyOf(List.of(request, this.simulation.timeout(patience)))
			.then(() -> afterWaiting(resource, request, user, finish));
	}

	private void afterWaiting(Resource resource, Request request, User user, Runnable finish) {
		if (!request.triggered()) {
			request.cancel();
			this.trace.log(user.name() + " gave up, queue=" + resource.queued());
			finish.run();
			return;
		}
		hold(resource, request, user, finish);
	}

	private void hold(Resource resource, Request request, User user, Runnable finish) {
		this.trace.log(user.name() + " holds");
		this.simulation.timeout(user.hold()).then(() -> {
			resource.release(request);
			this.trace.log(user.name() + " releases");
			finish.run();
		});
	}

	private void getter(Store<String> store, String name, double after, Runnable finish) {
		arrive(after, () -> store.get().thenAccept((item) -> {
			this.trace.log(name + " got " + item);
			finish.run();
		}));
	}

	private void putter(Store<String> store, Runnable finish) {
		this.simulation.timeout(1).then(() -> {
			store.put("x");
			this.trace.log("put x, items=" + store.size());
			Store.Get<String> late = store.get();
			this.trace.log("late get triggered=" + KernelCases.pythonBoolean(late.triggered()));
			this.simulation.timeout(1).then(() -> putMore(store, late, finish));
		});
	}

	private void putMore(Store<String> store, Store.Get<String> late, Runnable finish) {
		store.put("y");
		store.put("z");
		this.trace.log("put y and z, items=" + store.size());
		late.thenAccept((got) -> {
			this.trace.log("late got " + got);
			store.put("w");
			this.trace.log("put w, items=" + store.size());
			finish.run();
		});
	}

	private Run run() {
		long events = KernelCases.runAll(this.simulation);
		return new Run(this.trace.lines(), events);
	}

	/**
	 * A user of a resource.
	 *
	 * @param name its name in the trace
	 * @param after when it arrives
	 * @param hold how long it holds a slot
	 */
	private record User(String name, double after, double hold) {

	}

}
