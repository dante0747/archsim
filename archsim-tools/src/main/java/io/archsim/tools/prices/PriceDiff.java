package io.archsim.tools.prices;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.PythonFormat;

/**
 * What changed between two snapshots of a provider and region, in Markdown: the refresh's
 * pull request body. Each list's added, removed and repriced types, the rates that moved,
 * and the largest changes first.
 */
final class PriceDiff {

	private static final List<String> LIST_KEYS = SnapshotWriter.LISTS;

	private static final List<String> LIST_LABELS = List.of("EC2 / compute", "Databases", "Caches", "Streams");

	/**
	 * How many changes the summary shows, and how many added or removed types a list
	 * adds.
	 */
	private static final int TOP = 25;

	private static final double PERCENT = 100;

	private static final String TABLE_RULE = "|---|---:|---:|---:|";

	private PriceDiff() {
	}

	/**
	 * The summary of a snapshot against an older one, and whether any price or type
	 * changed; with no older one, a note that there is none and a change.
	 * @param old the older snapshot, when there is one
	 * @param current the new snapshot
	 * @return the summary
	 */
	static Summary summarize(Optional<JsonNode> old, JsonNode current) {
		List<String> lines = new ArrayList<>(heading(current));
		if (old.isEmpty()) {
			lines.add("No earlier snapshot of this provider and region to compare with.");
			return new Summary(text(lines), true);
		}
		lines.add("Compared with the snapshot of " + JsonValues.str(old.get().path("snapshotDate")) + ":");
		lines.add("");
		return compared(old.get(), current, lines);
	}

	// the table of the lists, then, when anything changed, the rates and types that did
	private static Summary compared(JsonNode old, JsonNode current, List<String> lines) {
		List<Move> moves = new ArrayList<>();
		lines.add("| List | Added | Removed | Price changed |");
		lines.add(TABLE_RULE);
		for (int index = 0; index < LIST_KEYS.size(); index++) {
			String list = LIST_KEYS.get(index);
			lines.add(compare(LIST_LABELS.get(index), prices(old, list), prices(current, list), moves));
		}
		List<String> rates = rates(old.path("rates"), current.path("rates"));
		lines.add("");
		if (JsonValues.equal(content(old), content(current))) {
			lines.add("**No prices changed**; only the date differs.");
			return new Summary(text(lines), false);
		}
		lines.addAll(rates);
		lines.addAll(moves(moves));
		return new Summary(text(lines), true);
	}

	private static List<String> heading(JsonNode current) {
		String where = JsonValues.str(current.path("provider")) + " " + JsonValues.str(current.path("region"));
		String counts = LIST_KEYS.stream()
			.map((list) -> LIST_LABELS.get(LIST_KEYS.indexOf(list)) + " "
					+ PythonFormat.format(current.path(list).size(), ","))
			.collect(Collectors.joining(", "));
		String sources = values(current.path("sources")).map((source) -> JsonValues.str(source.path("name")))
			.collect(Collectors.joining(", "));
		return List.of("## Price snapshot: " + where + ", " + JsonValues.str(current.path("snapshotDate")), "",
				"Types: " + counts + ". On-demand list prices from " + sources + ".", "");
	}

	// the list's row of the table, and its moves: repriced types, then
	// the first added and removed ones
	private static String compare(String label, SortedMap<PriceKey, Double> before, SortedMap<PriceKey, Double> after,
			List<Move> moves) {
		List<PriceKey> added = after.keySet().stream().filter((key) -> !before.containsKey(key)).toList();
		List<PriceKey> removed = before.keySet().stream().filter((key) -> !after.containsKey(key)).toList();
		List<PriceKey> moved = before.keySet()
			.stream()
			.filter((key) -> after.containsKey(key) && price(before, key) != price(after, key))
			.toList();
		moved.forEach((key) -> moves.add(Move.of(key, price(before, key), price(after, key))));
		added.stream().limit(TOP).forEach((key) -> moves.add(Move.added(key, price(after, key))));
		removed.stream().limit(TOP).forEach((key) -> moves.add(Move.removed(key, price(before, key))));
		return "| " + label + " | " + added.size() + " | " + removed.size() + " | " + moved.size() + " |";
	}

	// each type's price by its name and engines; a later entry of the same replaces it
	private static SortedMap<PriceKey, Double> prices(JsonNode snapshot, String list) {
		SortedMap<PriceKey, Double> prices = new TreeMap<>(PriceKey.ORDER);
		for (JsonNode type : snapshot.path(list)) {
			double hourly = JsonValues.floatOf(type.path("hourly").path("on_demand"));
			prices.put(PriceKey.of(type), hourly);
		}
		return prices;
	}

	private static double price(Map<PriceKey, Double> prices, PriceKey key) {
		return Objects.requireNonNull(prices.get(key), key::toString);
	}

	// the rates whose value moved, came or went, by name
	private static List<String> rates(JsonNode before, JsonNode after) {
		TreeSet<String> names = new TreeSet<>(CodePoints.ORDER);
		before.propertyNames().forEach(names::add);
		after.propertyNames().forEach(names::add);
		List<String> rows = names.stream()
			.filter((name) -> !JsonValues.equal(before.path(name), after.path(name)))
			.map((name) -> "| " + name + " | " + rate(before, name) + " | " + rate(after, name) + " |")
			.toList();
		if (rows.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(List.of("Rates:", "", "| Rate | Before | After |", "|---|---:|---:|"));
		lines.addAll(rows);
		lines.add("");
		return lines;
	}

	private static String rate(JsonNode rates, String name) {
		return rates.has(name) ? JsonValues.str(rates.path(name)) : "-";
	}

	// the largest changes first, an added or removed type after every repriced one
	private static List<String> moves(List<Move> moves) {
		if (moves.isEmpty()) {
			return List.of();
		}
		List<Move> sorted = moves.stream().sorted(Comparator.comparingDouble((move) -> -move.size())).toList();
		String shown = (sorted.size() > TOP) ? ", " + TOP + " of " + sorted.size() : "";
		List<String> lines = new ArrayList<>(List.of("Instance types (largest changes first" + shown + "):", "",
				"| Type | Before ($/h) | After ($/h) | Change |", TABLE_RULE));
		sorted.stream().limit(TOP).forEach((move) -> lines.add(move.row()));
		lines.add("");
		return lines;
	}

	// what the two snapshots hold but their dates
	private static JsonNode content(JsonNode snapshot) {
		JsonNode copy = snapshot.deepCopy();
		if (copy instanceof ObjectNode object) {
			object.remove("snapshotDate");
		}
		return copy;
	}

	private static Stream<JsonNode> values(JsonNode array) {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(array.iterator(), 0), false);
	}

	private static String text(List<String> lines) {
		return String.join("\n", lines) + "\n";
	}

	/**
	 * A summary, and whether anything changed.
	 *
	 * @param text the Markdown
	 * @param changed whether any price or type changed: false when only the date did, so
	 * that there is nothing to propose
	 */
	record Summary(String text, boolean changed) {

	}

	/**
	 * A type in a list: its name and, for a database, cache or broker type, its engines.
	 *
	 * @param sku the type
	 * @param engines its engines, joined by commas; empty for none
	 */
	private record PriceKey(String sku, String engines) {

		static final Comparator<PriceKey> ORDER = Comparator.comparing(PriceKey::sku, CodePoints.ORDER)
			.thenComparing(PriceKey::engines, CodePoints.ORDER);

		static PriceKey of(JsonNode type) {
			String engines = values(type.path("engines")).map(JsonValues::str).collect(Collectors.joining(","));
			return new PriceKey(JsonValues.str(type.path("sku")), engines);
		}

		String name() {
			return this.engines.isEmpty() ? this.sku : this.sku + " (" + this.engines + ")";
		}

	}

	/**
	 * A row of the changes' table, and how large the change is: its percentage either
	 * way, or -1 for a type added or removed, so that those come after every repriced
	 * one.
	 *
	 * @param size how large the change is
	 * @param row the row
	 */
	private record Move(double size, String row) {

		static Move of(PriceKey key, double before, double after) {
			double change = (before != 0) ? (after - before) / before * PERCENT : Double.POSITIVE_INFINITY;
			return new Move(Math.abs(change), "| " + key.name() + " | $" + PythonFormat.format(before, "g") + " | $"
					+ PythonFormat.format(after, "g") + " | " + PythonFormat.format(change, "+.1f") + "% |");
		}

		static Move added(PriceKey key, double after) {
			return new Move(-1, "| " + key.name() + " (new) | - | $" + PythonFormat.format(after, "g") + " | |");
		}

		static Move removed(PriceKey key, double before) {
			return new Move(-1, "| " + key.name() + " (gone) | $" + PythonFormat.format(before, "g") + " | - | |");
		}

	}

}
