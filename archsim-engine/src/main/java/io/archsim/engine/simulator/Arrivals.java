package io.archsim.engine.simulator;

import java.util.function.IntSupplier;

import io.archsim.engine.components.client.TrafficSourceRuntime;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.numeric.FloorDivision;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.ZipfSampler;
import io.archsim.engine.runtime.MessageLosses;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.document.ArrivalProcess;
import io.archsim.model.document.ConstantPattern;
import io.archsim.model.document.RequestType;
import io.archsim.model.document.Workload;

/**
 * The workload's user requests, as a routine: open-loop arrivals at the offered rate
 * (Poisson arrivals thinned to the pattern's rate, or evenly spaced ones), each a read or
 * a write of a key, sent by a client group picked by its share. A key is drawn from the
 * workload's Zipf keyspace when a part uses keys, else every request has key 0. Every
 * n-th request is traced, so about 1,200 are, drawing no random number.
 */
final class Arrivals {

	private static final double MS_PER_SECOND = 1000.0;

	/** How many requests a run traces, sampled evenly. */
	private static final double TRACE_TARGET = 1200;

	/** How far an evenly spaced workload looks ahead while its rate is 0. */
	private static final double IDLE_STEP_MS = 10.0;

	private final Simulation simulation;

	private final Workload workload;

	private final Timeline timeline;

	private final ClientPicker clients;

	private final Streams streams;

	private final IntSupplier keys;

	private final double peak;

	private final double durationMs;

	private final long traceEvery;

	private final MessageLosses losses;

	private long sent;

	private double next;

	/**
	 * The arrivals of a run.
	 * @param context the run
	 * @param workload its workload
	 * @param clients the client groups that send the requests
	 */
	Arrivals(SimContext context, Workload workload, ClientPicker clients) {
		this.simulation = context.simulation();
		this.losses = context.messageLosses();
		this.workload = workload;
		this.timeline = context.timeline();
		this.clients = clients;
		this.streams = new Streams(context);
		this.keys = keys(context, workload);
		this.peak = workload.peakRate();
		this.durationMs = workload.durationS() * MS_PER_SECOND;
		this.traceEvery = Math.max(1, (long) FloorDivision.of(workload.expectedArrivals(), TRACE_TARGET));
	}

	/**
	 * Starts sending: the routine ends at the end of the arrival period.
	 * @param finish what ends the routine
	 */
	void start(Runnable finish) {
		if (this.peak <= 0) {
			finish.run();
			return;
		}
		sendNext(finish);
	}

	private void sendNext(Runnable finish) {
		if (!advance()) {
			finish.run();
			return;
		}
		this.simulation.timeout(this.next - this.simulation.now()).then(() -> {
			send();
			sendNext(finish);
		});
	}

	/**
	 * The requests' keys: drawn from the workload's Zipf keyspace when a part uses keys,
	 * else key 0 for every request.
	 * @param context the run, its parts built
	 * @param workload its workload
	 * @return what draws each request's key
	 */
	private static IntSupplier keys(SimContext context, Workload workload) {
		if (context.components().stream().noneMatch(RuntimeComponent::usesKeys)) {
			return () -> 0;
		}
		ZipfSampler sampler = new ZipfSampler(workload.keyCount(), workload.keySkew());
		PythonRandom stream = context.stream("keys");
		return () -> sampler.sample(stream);
	}

	/**
	 * Moves to the next arrival's time.
	 * @return {@code false} when it falls past the arrival period
	 */
	private boolean advance() {
		return (this.workload.arrivalProcess() == ArrivalProcess.POISSON) ? advancePoisson() : advanceEvenly();
	}

	private boolean advancePoisson() {
		boolean steady = this.workload.pattern() instanceof ConstantPattern;
		while (true) {
			this.next += this.streams.arrivals().exponential(this.peak / MS_PER_SECOND);
			if (this.next >= this.durationMs) {
				return false;
			}
			if (steady || this.streams.thinning().nextDouble() * this.peak < rateAtNext()) {
				return true;
			}
		}
	}

	private boolean advanceEvenly() {
		while (true) {
			double rate = rateAtNext();
			if (rate > 0) {
				this.next += MS_PER_SECOND / rate;
				return this.next < this.durationMs;
			}
			this.next += IDLE_STEP_MS;
			if (this.next >= this.durationMs) {
				return false;
			}
		}
	}

	private double rateAtNext() {
		return this.workload.rateAt(this.next / MS_PER_SECOND);
	}

	private void send() {
		this.sent++;
		RequestType operation = (this.streams.mix().nextDouble() < this.workload.readRatio()) ? RequestType.READ
				: RequestType.WRITE;
		int key = this.keys.getAsInt();
		double now = this.simulation.now();
		Request.Arrival arrival = new Request.Arrival(this.sent, operation, key, now, this.timeline.inWindow(now));
		Request request = (this.sent % this.traceEvery == 0) ? Request.withTrace(arrival, this.losses)
				: Request.withoutTrace(arrival, this.losses);
		TrafficSourceRuntime source = this.clients.next();
		this.simulation.start((finish) -> source.submit(request, finish));
	}

	/**
	 * The arrival process's random streams.
	 */
	private record Streams(PythonRandom arrivals, PythonRandom thinning, PythonRandom mix) {

		Streams(SimContext context) {
			this(context.stream("arrivals"), context.stream("arrivals:thinning"), context.stream("mix"));
		}

	}

}
