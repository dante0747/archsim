package io.archsim.engine.calibration.prometheus;

import java.io.Serial;

/**
 * What a Prometheus server returned cannot be used. The message never repeats what the
 * server said: it may come from a server the person calibrating does not control.
 */
public final class PrometheusException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A refusal in the Python engine's words.
	 * @param message what is wrong
	 */
	public PrometheusException(String message) {
		super(message);
	}

}
