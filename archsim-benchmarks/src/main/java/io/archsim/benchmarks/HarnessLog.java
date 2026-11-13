package io.archsim.benchmarks;

import java.io.PrintWriter;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * The harness's log: one line per step, stamped with the local time, written at once so
 * that a campaign's progress shows while it runs.
 */
public final class HarnessLog {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final PrintWriter out;

	private final Clock clock;

	/**
	 * A log written to a stream.
	 * @param out the stream
	 * @param clock the clock that stamps each line
	 */
	public HarnessLog(PrintWriter out, Clock clock) {
		this.out = out;
		this.clock = clock;
	}

	/**
	 * The clock that stamps the log's lines, which also dates the files the harness
	 * writes.
	 * @return the clock
	 */
	public Clock clock() {
		return this.clock;
	}

	/**
	 * Writes a line: {@code [12:34:56] message}.
	 * @param message the message
	 */
	public void line(String message) {
		this.out.println('[' + LocalTime.now(this.clock).format(TIME) + "] " + message);
		this.out.flush();
	}

}
