package io.archsim.engine.simulator;

import java.util.ArrayList;
import java.util.List;

import io.archsim.model.document.BurstPattern;
import io.archsim.model.document.ConstantPattern;
import io.archsim.model.document.RampPattern;
import io.archsim.model.document.TracePattern;
import io.archsim.model.document.TracePoint;
import io.archsim.model.document.Workload;

/**
 * Where evenly spaced arrivals fall: each one where the offered rate, added up since the
 * last, reaches one request. Every pattern's rate is linear piece by piece, so the time
 * is found in closed form on each piece: a ramp or a trace that starts at 0 sends its
 * first request once its rate has added up to one, and a constant rate keeps its gaps of
 * {@code 1000 / rate} ms, computed as they always were.
 */
final class EvenSpacing {

	private static final double MS_PER_SECOND = 1000.0;

	private final List<Piece> pieces;

	/** The piece the last arrival fell on. */
	private int current;

	/** The last arrival's time, in ms: 0 before the first. */
	private double atMs;

	private EvenSpacing(List<Piece> pieces) {
		this.pieces = pieces;
	}

	/**
	 * The spacing of a workload's arrivals.
	 * @param workload the workload
	 * @return its spacing
	 */
	static EvenSpacing of(Workload workload) {
		double durationMs = workload.durationS() * MS_PER_SECOND;
		List<Piece> pieces = switch (workload.pattern()) {
			case ConstantPattern() -> List.of(Piece.flat(0.0, workload.rps()));
			case RampPattern ramp ->
				List.of(new Piece(0.0, ramp.startRps(), (ramp.endRps() - ramp.startRps()) / durationMs),
						Piece.flat(durationMs, ramp.endRps()));
			case BurstPattern burst -> burst(workload.rps(), burst);
			case TracePattern trace -> trace(trace);
		};
		return new EvenSpacing(pieces);
	}

	/**
	 * Moves to the next arrival: the first after 0, then each after the last.
	 * @return the next arrival's time, in ms, or positive infinity when the rate adds up
	 * to no other request
	 */
	double next() {
		double requests = 1.0;
		while (true) {
			Piece piece = this.pieces.get(this.current);
			boolean last = this.current + 1 == this.pieces.size();
			double endMs = last ? Double.POSITIVE_INFINITY : this.pieces.get(this.current + 1).startMs();
			double rate = piece.rateAt(this.atMs);
			double step = piece.msFor(rate, requests);
			if (step < endMs - this.atMs) {
				this.atMs += step;
				return this.atMs;
			}
			if (last) {
				this.atMs = Double.POSITIVE_INFINITY;
				return this.atMs;
			}
			requests -= piece.requests(rate, endMs - this.atMs);
			this.atMs = endMs;
			this.current++;
		}
	}

	private static List<Piece> burst(double baseRps, BurstPattern burst) {
		double startMs = burst.startS() * MS_PER_SECOND;
		double endMs = (burst.startS() + burst.durationS()) * MS_PER_SECOND;
		List<Piece> pieces = new ArrayList<>();
		if (startMs > 0) {
			pieces.add(Piece.flat(0.0, baseRps));
		}
		pieces.add(Piece.flat(startMs, burst.burstRps()));
		pieces.add(Piece.flat(endMs, baseRps));
		return List.copyOf(pieces);
	}

	private static List<Piece> trace(TracePattern trace) {
		List<TracePoint> points = trace.points();
		List<Piece> pieces = new ArrayList<>();
		for (int i = 1; i < points.size(); i++) {
			TracePoint start = points.get(i - 1);
			TracePoint end = points.get(i);
			double startMs = start.t() * MS_PER_SECOND;
			double spanMs = end.t() * MS_PER_SECOND - startMs;
			// two points in the same millisecond leave no span, and a piece there
			// would have a slope that is not a number
			if (!(spanMs > 0)) {
				continue;
			}
			pieces.add(new Piece(startMs, start.rps(), (end.rps() - start.rps()) / spanMs));
		}
		TracePoint last = points.getLast();
		pieces.add(Piece.flat(last.t() * MS_PER_SECOND, last.rps()));
		return List.copyOf(pieces);
	}

	/**
	 * A piece of the rate, linear from its start until the next piece's.
	 *
	 * @param startMs where it starts, in ms
	 * @param startRps its rate there, in requests per second
	 * @param slope how much its rate rises each ms, in requests per second per ms
	 */
	private record Piece(double startMs, double startRps, double slope) {

		static Piece flat(double startMs, double rps) {
			return new Piece(startMs, rps, 0.0);
		}

		double rateAt(double atMs) {
			return (this.slope == 0.0) ? this.startRps : this.startRps + this.slope * (atMs - this.startMs);
		}

		/**
		 * How long the rate takes to add up to some requests, on this piece's line.
		 * @param rate the rate where it starts, in requests per second
		 * @param requests how many requests
		 * @return the time, in ms: 0 when the pieces before added up to them already, or
		 * positive infinity when the line never adds up to them
		 */
		double msFor(double rate, double requests) {
			if (requests <= 0) {
				// the pieces before added up to the request already: it is due here
				return 0.0;
			}
			if (this.slope == 0.0) {
				// on a constant rate, the gap of 1000 / rate ms the arrivals always had
				return (rate > 0) ? MS_PER_SECOND * requests / rate : Double.POSITIVE_INFINITY;
			}
			// rate × t + slope × t² / 2 = 1000 × requests, its root written to
			// stay stable where the slope is small or the rate is 0
			double needed = MS_PER_SECOND * requests;
			double discriminant = rate * rate + 2 * this.slope * needed;
			if (discriminant < 0) {
				return Double.POSITIVE_INFINITY;
			}
			double denominator = rate + Math.sqrt(discriminant);
			return (denominator > 0) ? 2 * needed / denominator : Double.POSITIVE_INFINITY;
		}

		/**
		 * The requests the rate adds up to over a span of this piece.
		 * @param rate the rate where the span starts, in requests per second
		 * @param spanMs how long the span is, in ms
		 * @return the requests
		 */
		double requests(double rate, double spanMs) {
			return (rate * spanMs + this.slope * spanMs * spanMs / 2) / MS_PER_SECOND;
		}

	}

}
