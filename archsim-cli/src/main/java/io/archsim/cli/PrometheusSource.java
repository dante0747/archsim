package io.archsim.cli;

import org.jspecify.annotations.Nullable;

/**
 * Where {@code archsim calibrate prometheus} reads its responses: from a server, or from
 * responses recorded earlier.
 */
sealed interface PrometheusSource {

	/**
	 * The source the options name, as the Python CLI chose it: the recording when one is
	 * named, else the server, even one not named, which is then refused for its URL.
	 * @param url {@code --url}, if given
	 * @param recorded {@code --recorded}, if given
	 * @return the source
	 */
	static PrometheusSource of(@Nullable String url, @Nullable String recorded) {
		String file = PythonOptions.given(recorded);
		return (file != null) ? new Recorded(file) : new Server((url != null) ? url : "");
	}

	/**
	 * A Prometheus server.
	 *
	 * @param url its base URL
	 */
	record Server(String url) implements PrometheusSource {

	}

	/**
	 * Responses recorded earlier ({@code --record}).
	 *
	 * @param file the recording, as given
	 */
	record Recorded(String file) implements PrometheusSource {

	}

}
