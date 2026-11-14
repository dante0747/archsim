package io.archsim.tools.prices;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;

/**
 * What {@code scripts/prices} wrote for the tests' samples, recorded once from the Python
 * before stage 7 removed it: snapshots and price change summaries, byte for byte.
 */
final class Recorded {

	private Recorded() {
	}

	static String text(String name) {
		try (InputStream in = Objects.requireNonNull(Recorded.class.getResourceAsStream(name), name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static JsonNode json(String name) {
		return PythonJsonReader.read(text(name));
	}

}
