package io.archsim.tools.prices;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.PythonJson;
import io.archsim.model.text.PythonText;

/**
 * Writes a snapshot as {@code <provider>-<region>-<date>.json} among the ones
 * archsim-model ships, or to a file given, in {@code json.dumps(snapshot, indent=1)}'s
 * text with its keys in the order {@code scripts/prices} gave them: the lists it was
 * given, its rates, {@code format}, {@code currency} and {@code snapshotDate}, then the
 * lists it was not given, empty. Whether the model reads it is archsim-model's tests' to
 * say, which the refresh workflow runs on it.
 */
final class SnapshotWriter {

	/** A snapshot's lists of types, in the order they are deduplicated and counted. */
	static final List<String> LISTS = List.of("compute", "databases", "caches", "streams");

	/** The snapshots' format, which archsim-model reads. */
	static final String FORMAT = "archsim.prices/1";

	private static final String CURRENCY = "USD";

	private final Path snapshots;

	private final Clock clock;

	private final PrintWriter err;

	/**
	 * A writer.
	 * @param snapshots the folder of the snapshots archsim-model ships
	 * @param clock the clock, whose day dates the snapshot
	 * @param err where it says what it wrote
	 */
	SnapshotWriter(Path snapshots, Clock clock, PrintWriter err) {
		this.snapshots = snapshots;
		this.clock = clock;
		this.err = err;
	}

	/**
	 * Writes a snapshot dated today, its types deduplicated and in order
	 * ({@link SkuList#inOrder}), and says where and how many types of each list.
	 * @param snapshot the snapshot
	 * @param out the file; empty for its own name among the snapshots
	 * @return the file written
	 * @throws IOException when it cannot be written
	 * @throws PriceListException when a type and engine has two prices
	 */
	Path write(Snapshot snapshot, Optional<Path> out) throws IOException {
		String date = LocalDate.now(this.clock).toString();
		ObjectNode json = json(snapshot, date);
		Path path = out.orElseGet(
				() -> this.snapshots.resolve(snapshot.provider() + "-" + snapshot.region() + "-" + date + ".json"));
		Files.writeString(path, PythonJson.writeIndented(json, 1) + "\n", StandardCharsets.UTF_8);
		ObjectNode counts = JsonNodeFactory.instance.objectNode();
		LISTS.forEach((list) -> counts.put(list, json.path(list).size()));
		this.err.println("wrote " + path + " " + PythonText.str(counts));
		return path;
	}

	private static ObjectNode json(Snapshot snapshot, String date) {
		ObjectNode json = JsonNodeFactory.instance.objectNode();
		json.put("provider", snapshot.provider());
		json.put("region", snapshot.region());
		ArrayNode sources = json.putArray("sources");
		snapshot.sources().forEach((source) -> sources.addObject().put("name", source.name()).put("url", source.url()));
		json.put("note", snapshot.note());
		snapshot.lists().forEach((list, types) -> json.set(list, types(types, list)));
		snapshot.rates().ifPresent((rates) -> json.set("rates", rates(rates)));
		json.put("format", FORMAT);
		json.put("currency", CURRENCY);
		json.put("snapshotDate", date);
		LISTS.stream().filter((list) -> !json.has(list)).forEach((list) -> json.set(list, types(List.of(), list)));
		return json;
	}

	private static ArrayNode types(List<Sku> types, String list) {
		ArrayNode json = JsonNodeFactory.instance.arrayNode();
		SkuList.inOrder(types, list).forEach((type) -> json.add(type.toJson()));
		return json;
	}

	private static ObjectNode rates(Map<String, Double> rates) {
		ObjectNode json = JsonNodeFactory.instance.objectNode();
		rates.forEach(json::put);
		return json;
	}

}
