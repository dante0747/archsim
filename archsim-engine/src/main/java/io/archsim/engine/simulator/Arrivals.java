package io.archsim.engine.simulator;

import io.archsim.engine.components.client.TrafficSourceRuntime;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.numeric.FloorDivision;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.document.ArrivalProcess;
import io.archsim.model.document.ConstantPattern;
import io.archsim.model.document.RequestType;
import io.archsim.model.document.Workload;

/**
 * The workload's user requests, as a routine: open-loop arrivals at the offered rate
 * (Poisson arrivals thinned to the pattern's rate, or evenly spaced ones), each a read or
 * a write of a key, sent by a client group picked by its share. Every n-th request is
 * traced, so about 1,200 are, drawing no random number.
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

	private final double peak;

	private final double durationMs;

	private final long traceEvery;

	private long sent;

	private double next;

	Arrivals(SimContext context, Workload workload, ClientPicker clients) {
		this.simulation = context.simulation();
		this.workload = workload;
		this.timeline = context.timeline();
		this.clients = clients;
		this.streams = new Streams(context);
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
		double now = this.simulation.now();
		Request.Arrival arrival = new Request.Arrival(this.sent, operation, 0, now, this.timeline.inWindow(now));
		Request request = (this.sent % this.traceEvery == 0) ? Request.withTrace(arrival)
				: Request.withoutTrace(arrival);
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
