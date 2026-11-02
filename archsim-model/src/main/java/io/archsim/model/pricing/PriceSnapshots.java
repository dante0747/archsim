package io.archsim.model.pricing;

import io.archsim.model.text.PythonText;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The price snapshots this machine has: the ones ArchSim ships, and any in extra folders
 * (the Python engine's {@code ARCHSIM_PRICES_DIR}), extra folders first. A file that is
 * not a valid snapshot is not offered.
 *
 * <p>
 * A document's {@code pricing} names a snapshot by provider and region, and may pin it by
 * date and content hash: then exactly that one is used, never another, or none.
 */
public final class PriceSnapshots {

	/** The provider templates and new documents price with. */
	public static final String DEFAULT_PROVIDER = "aws";

	/** The region templates and new documents price with. */
	public static final String DEFAULT_REGION = "us-east-1";

	/**
	 * The snapshots ArchSim ships, in this package's {@code snapshots/} resources, in
	 * file order.
	 */
	static final List<String> SHIPPED = List.of("aws-us-east-1-2026-10-27.json", "aws-us-east-1-2026-10-28.json");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private record Found(PriceSnapshot snapshot, String file, boolean shipped) {
	}

	private final List<Found> found;

	private PriceSnapshots(List<Found> found) {
		this.found = List.copyOf(found);
	}

	/** The snapshots ArchSim ships, read once. */
	public static PriceSnapshots shipped() {
		return Shipped.SNAPSHOTS;
	}

	/**
	 * The snapshots in these folders ({@code *.json}, by file name), then the shipped
	 * ones.
	 */
	public static PriceSnapshots with(List<Path> folders) {
		List<Found> all = new ArrayList<>();
		for (Path folder : folders) {
			if (!Files.isDirectory(folder)) {
				continue;
			}
			try (Stream<Path> files = Files.list(folder)) {
				for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
					read(file).ifPresent(s -> all.add(new Found(s, file.getFileName().toString(), false)));
				}
			}
			catch (IOException e) {
				throw new UncheckedIOException("cannot list " + folder, e);
			}
		}
		all.addAll(shipped().found);
		return new PriceSnapshots(all);
	}

	private static Optional<PriceSnapshot> read(Path file) {
		try {
			return Optional.of(PriceSnapshot.parse(JSON.readTree(file.toFile())));
		}
		catch (PricingException | JacksonException e) {
			return Optional.empty(); // not a snapshot: not offered
		}
	}

	/**
	 * The snapshots that can be chosen: the newest of each provider and region, or
	 * ({@code
	 * everyDate}) all, by provider and region, newest first.
	 */
	public List<AvailableSnapshot> available(boolean everyDate) {
		Map<List<String>, Found> unique = new LinkedHashMap<>();
		for (Found f : found) {
			unique.putIfAbsent(key(f.snapshot()), f); // the same content in two folders:
														// listed once
		}
		Map<List<String>, String> newest = new HashMap<>();
		unique.keySet().forEach(k -> newest.merge(k.subList(0, 2), k.get(2), (a, b) -> a.compareTo(b) >= 0 ? a : b));
		Comparator<List<String>> order = Comparator.<List<String>, String>comparing(k -> k.get(0))
			.thenComparing(k -> k.get(1))
			.thenComparing(k -> k.get(2), Comparator.reverseOrder())
			.thenComparing(k -> k.get(3));
		List<AvailableSnapshot> out = new ArrayList<>();
		for (List<String> k : unique.keySet().stream().sorted(order).toList()) {
			boolean isNewest = Objects.equals(newest.get(k.subList(0, 2)), k.get(2));
			if (isNewest || everyDate) {
				Found f = Objects.requireNonNull(unique.get(k));
				out.add(new AvailableSnapshot(k.get(0), k.get(1), k.get(2), f.file(), k.get(3), isNewest));
			}
		}
		return out;
	}

	private static List<String> key(PriceSnapshot s) {
		return List.of(s.provider(), Objects.requireNonNullElse(s.region(), ""),
				Objects.requireNonNullElse(s.snapshotDate(), ""), Objects.requireNonNullElse(s.sha256(), ""));
	}

	/**
	 * The newest snapshot of {@code provider} and {@code region} ({@code generic} needs
	 * none); with {@code date} (and {@code sha256}) exactly that snapshot, never another.
	 * @throws PricingException naming what this machine has, when there is none
	 */
	public PriceSnapshot load(String provider, @Nullable String region, @Nullable String date,
			@Nullable String sha256) {
		if (provider.equals("generic")) {
			return PriceSnapshot.GENERIC;
		}
		PriceSnapshot best = null;
		for (Found f : found) {
			PriceSnapshot s = f.snapshot();
			boolean matches = s.provider().equals(provider) && (region == null || region.equals(s.region()))
					&& (date == null || date.equals(s.snapshotDate())) && (sha256 == null || sha256.equals(s.sha256()));
			if (matches && (best == null || dateOf(s).compareTo(dateOf(best)) > 0)) {
				best = s;
			}
		}
		if (best != null) {
			return best;
		}
		throw new PricingException(missing(provider, region, date, sha256));
	}

	private String missing(String provider, @Nullable String region, @Nullable String date, @Nullable String sha256) {
		String wanted = "no price snapshot for %s %s".formatted(provider, region == null ? "" : region).stripTrailing();
		if (date != null) {
			wanted += " of " + date;
		}
		if (sha256 != null) {
			wanted += " with content " + sha256.substring(0, Math.min(12, sha256.length()));
		}
		List<AvailableSnapshot> same = available(true).stream()
			.filter(a -> a.provider().equals(provider) && (region == null || a.region().equals(region)))
			.toList();
		if ((date != null || sha256 != null) && !same.isEmpty()) {
			String have = same.stream()
				.map(a -> "%s %s (%s)".formatted(a.region(), a.date(), a.sha256().substring(0, 12)))
				.collect(Collectors.joining(", "));
			return "%s (this machine has %s %s); a pinned snapshot is never swapped for another.".formatted(wanted,
					provider, have);
		}
		String have = available(false).stream()
			.map(a -> a.provider() + " " + a.region())
			.collect(Collectors.joining(", "));
		return "%s (available: generic; snapshots: %s).".formatted(wanted, have.isEmpty() ? "none" : have);
	}

	private static String dateOf(PriceSnapshot s) {
		return Objects.requireNonNullElse(s.snapshotDate(), "");
	}

	/**
	 * The snapshot a document's {@code pricing} JSON names (generic when it has none):
	 * the pinned one when it has {@code snapshotDate} (and {@code snapshotSha256}), else
	 * the newest of its provider and region.
	 * @throws PricingException when this machine does not have it
	 */
	public PriceSnapshot forPricing(@Nullable JsonNode pricing) {
		JsonNode p = pricing == null || pricing.isNull() ? JsonNodeFactory.instance.objectNode() : pricing;
		JsonNode provider = p.path("provider");
		return load(PythonText.truthy(provider) ? PythonText.str(provider) : "generic", text(p.get("region")),
				text(p.get("snapshotDate")), text(p.get("snapshotSha256")));
	}

	private static @Nullable String text(@Nullable JsonNode value) {
		return value == null || value.isNull() ? null : PythonText.str(value);
	}

	/**
	 * {@code pricing} with the snapshot it resolves to written in ({@code region}, {@code
	 * snapshotDate}, {@code snapshotSha256}), so a run's input names exactly the prices
	 * it used. Unchanged for generic prices, or when this machine has no such snapshot.
	 */
	public @Nullable JsonNode pinned(@Nullable JsonNode pricing) {
		JsonNode p = pricing == null || pricing.isNull() ? JsonNodeFactory.instance.objectNode() : pricing;
		JsonNode provider = p.path("provider");
		if (!p.isObject() || !PythonText.truthy(provider) || PythonText.str(provider).equals("generic")) {
			return pricing;
		}
		PriceSnapshot snapshot;
		try {
			snapshot = forPricing(p);
		}
		catch (PricingException missing) {
			return pricing;
		}
		ObjectNode written = ((ObjectNode) p).deepCopy();
		written.put("region", snapshot.region());
		written.put("snapshotDate", snapshot.snapshotDate());
		written.put("snapshotSha256", snapshot.sha256());
		return written;
	}

	/**
	 * The {@code pricing} templates and new documents start with: the newest shipped
	 * snapshot of {@value #DEFAULT_PROVIDER} {@value #DEFAULT_REGION}, pinned by date;
	 * generic rates when none is shipped.
	 */
	public ObjectNode defaultPricing() {
		String newest = found.stream()
			.filter(Found::shipped)
			.map(Found::snapshot)
			.filter(s -> s.provider().equals(DEFAULT_PROVIDER) && DEFAULT_REGION.equals(s.region()))
			.map(PriceSnapshots::dateOf)
			.max(Comparator.naturalOrder())
			.orElse(null);
		ObjectNode pricing = JsonNodeFactory.instance.objectNode();
		if (newest == null) {
			return pricing.put("provider", "generic");
		}
		return pricing.put("provider", DEFAULT_PROVIDER).put("region", DEFAULT_REGION).put("snapshotDate", newest);
	}

	/** The shipped snapshots, read the first time they are needed. */
	private static final class Shipped {

		static final PriceSnapshots SNAPSHOTS = new PriceSnapshots(
				SHIPPED.stream().map(name -> new Found(PriceSnapshot.parse(resource(name)), name, true)).toList());

		private static JsonNode resource(String name) {
			try (InputStream in = PriceSnapshots.class.getResourceAsStream("snapshots/" + name)) {
				if (in == null) {
					throw new IllegalStateException("the shipped snapshot " + name + " is missing from the jar");
				}
				return JSON.readTree(in);
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

	}

}
