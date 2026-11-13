package io.archsim.benchmarks;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * A harness log a test reads back, stamped at noon.
 */
public final class Logs {

	private final StringWriter written = new StringWriter();

	private final HarnessLog log = new HarnessLog(new PrintWriter(this.written),
			Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC));

	/**
	 * The log.
	 * @return the log
	 */
	public HarnessLog log() {
		return this.log;
	}

	/**
	 * What the log holds.
	 * @return its text
	 */
	public String text() {
		return this.written.toString();
	}

}
