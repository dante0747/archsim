package io.archsim.engine.calibration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.parity.Repository;

/**
 * The example the calibration tests calibrate, as the Python engine's probes read it:
 * {@code examples/cache-aside-starter.json}.
 */
final class StarterDesign {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private StarterDesign() {
	}

	/**
	 * Reads the example.
	 * @return the design
	 */
	static JsonNode read() {
		try {
			return JSON.readTree(Files.readString(Repository.examples().resolve("cache-aside-starter.json")));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
