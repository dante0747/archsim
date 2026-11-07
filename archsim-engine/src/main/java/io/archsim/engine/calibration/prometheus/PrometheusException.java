package io.archsim.engine.calibration.prometheus;

import java.io.Serial;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * What a Prometheus server returned cannot be used. The message never repeats what the
 * server said: it may come from a server the person calibrating does not control.
 */
public final class PrometheusException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/** What the server said; {@code null} for nothing. */
	private final @Nullable String detail;

	/**
	 * A refusal in the Python engine's words.
	 * @param message what is wrong
	 */
	public PrometheusException(String message) {
		this(message, null);
	}

	/**
	 * A refusal, with what the server said.
	 * @param message what is wrong, in the Python engine's words
	 * @param detail what the server said; {@code null} for nothing
	 */
	public PrometheusException(String message, @Nullable String detail) {
		super(message);
		this.detail = detail;
	}

	/**
	 * What the server said, for whoever named the server (the command line); never for a
	 * request's answer, since the server may not be the caller's.
	 * @return what it said; empty for nothing
	 */
	public Optional<String> detail() {
		return Optional.ofNullable(this.detail);
	}

}
