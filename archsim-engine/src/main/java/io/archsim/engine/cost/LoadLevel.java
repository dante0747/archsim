package io.archsim.engine.cost;

import io.archsim.engine.load.BaseRate;
import io.archsim.model.document.Workload;

/**
 * A load a design is priced at: the workload's steady base, or its peak, which a month
 * bills for the workload's peak hours (ADR-0074).
 */
enum LoadLevel {

	/**
	 * The steady base load: a constant pattern's rate, a burst's outside it, a ramp's low
	 * end, a trace's median.
	 */
	BASE("base load"),

	/** The pattern's peak: a burst's rate, a ramp's high end, a trace's highest point. */
	PEAK("peak");

	/**
	 * A time bucket whose offered rate is within this factor of a level's rate is at it.
	 */
	private static final double BUCKET_SLACK = 1.25;

	/** How far past the slack an offered rate may round and still be at the level. */
	private static final double ROUNDING = 1e-9;

	private final String words;

	LoadLevel(String words) {
		this.words = words;
	}

	/**
	 * The level's rate.
	 * @param workload the workload
	 * @return its requests per second
	 */
	double rate(Workload workload) {
		return (this == BASE) ? BaseRate.of(workload) : workload.peakRate();
	}

	/**
	 * Whether a time bucket's offered rate is at the level: at most the slack above the
	 * base, or at most the slack below the peak.
	 * @param offered the bucket's offered rate
	 * @param rate the level's rate
	 * @return {@code true} when it is
	 */
	boolean holds(double offered, double rate) {
		return (this == BASE) ? offered <= rate * BUCKET_SLACK + ROUNDING : offered >= rate / BUCKET_SLACK - ROUNDING;
	}

	/**
	 * The level in words, such as {@code base load}.
	 * @return the words
	 */
	String words() {
		return this.words;
	}

}
