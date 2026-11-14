package io.archsim.tools.prices;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.JsonDecodeException;
import io.archsim.engine.text.PythonJsonReader;

/**
 * The snapshot a new one is compared with when none is named: the newest older snapshot
 * of the same provider and region, beside it.
 */
final class Baselines {

	private Baselines() {
	}

	/**
	 * The newest snapshot older than this one of the same provider and region, in its
	 * folder. Files that are not JSON objects are passed over.
	 * @param current the new snapshot
	 * @return the older snapshot, or empty when there is none
	 * @throws IOException when the new snapshot or its folder cannot be read
	 * @throws JsonDecodeException when the new snapshot is not JSON
	 */
	static Optional<Path> baselineFor(Path current) throws IOException {
		JsonNode snapshot = read(current);
		String date = JsonValues.strOrEmpty(snapshot.path("snapshotDate"));
		Optional<Path> best = Optional.empty();
		String bestDate = "";
		for (Path file : others(current)) {
			Optional<JsonNode> other = readOrSkip(file);
			String otherDate = other.map((json) -> JsonValues.strOrEmpty(json.path("snapshotDate"))).orElse("");
			boolean older = CodePoints.ORDER.compare(otherDate, date) < 0;
			boolean newer = best.isEmpty() || CodePoints.ORDER.compare(otherDate, bestDate) > 0;
			if (other.isPresent() && isSameRegion(other.get(), snapshot) && older && newer) {
				best = Optional.of(file);
				bestDate = otherDate;
			}
		}
		return best;
	}

	// the folder's other JSON files, by name, so that two of the same date pick one alike
	private static List<Path> others(Path current) throws IOException {
		Path folder = current.toAbsolutePath().getParent();
		List<Path> others = new ArrayList<>();
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : files.filter((path) -> path.getFileName().toString().endsWith(".json"))
				.sorted()
				.toList()) {
				if (!Files.isSameFile(file, current)) {
					others.add(file);
				}
			}
		}
		return others;
	}

	private static boolean isSameRegion(JsonNode other, JsonNode snapshot) {
		return JsonValues.equal(other.path("provider"), snapshot.path("provider"))
				&& JsonValues.equal(other.path("region"), snapshot.path("region"));
	}

	// a file that cannot be read, is not JSON or is not an object is no snapshot
	private static Optional<JsonNode> readOrSkip(Path file) {
		try {
			JsonNode json = read(file);
			return json.isObject() ? Optional.of(json) : Optional.empty();
		}
		catch (IOException | JsonDecodeException ex) {
			return Optional.empty();
		}
	}

	private static JsonNode read(Path file) throws IOException {
		return PythonJsonReader.read(Files.readString(file, StandardCharsets.UTF_8));
	}

}
