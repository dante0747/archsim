package io.archsim.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.parity.GoldenEntry;

/**
 * The engine's extra parity cases: files of calls with the frozen engine's output, which
 * scripts in {@code scripts/}, since deleted, recorded for what the golden files do not
 * reach, read from the test resources.
 */
final class CaseFiles {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private CaseFiles() {
	}

	/**
	 * A case file's calls, as written.
	 * @param cases the file, under {@code io/archsim/engine/} in the test resources
	 * @return its calls, each with its {@code name}, {@code fn}, {@code input}, and the
	 * {@code output} it returned or the {@code error} it raised
	 */
	static JsonNode read(String cases) {
		try (InputStream file = Objects
			.requireNonNull(CaseFiles.class.getResourceAsStream("/io/archsim/engine/" + cases), cases)) {
			return JSON.readTree(file);
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read " + cases, ex);
		}
	}

	/**
	 * A case file's calls, as golden entries to check as the golden files are checked:
	 * what each returned, or what it raised.
	 * @param cases the file, under {@code io/archsim/engine/} in the test resources
	 * @return its calls, in order
	 */
	static List<GoldenEntry> entries(String cases) {
		List<GoldenEntry> entries = new ArrayList<>();
		for (JsonNode call : read(cases)) {
			entries.add(new GoldenEntry(cases, call.get("name").stringValue(), call.get("fn").stringValue(),
					(ObjectNode) call.get("input"), call.get("output"), call.get("error")));
		}
		return entries;
	}

}
