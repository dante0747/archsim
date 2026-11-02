package io.archsim.model.pricing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.text.CanonicalJson;

/**
 * The on-demand prices of one provider and region on one day, and the instance types they
 * price; or ArchSim's generic illustrative rates ({@link #GENERIC}), which price no
 * instance types.
 *
 * @param provider the provider, such as {@code aws}, or {@code generic}
 * @param region the region; {@code null} for generic rates
 * @param snapshotDate the day; {@code null} for generic rates
 * @param sources where the prices came from
 * @param currency always {@code USD}
 * @param illustrative whether the prices are ArchSim's generic ones
 * @param note a remark
 * @param skus the instance types, by price list
 * @param rates per-unit prices by name, {@code null} where the snapshot has none
 * @param sha256 the hash of the file's content; {@code null} for generic rates
 */
public record PriceSnapshot(String provider, @Nullable String region, @Nullable String snapshotDate,
		List<PriceSource> sources, String currency, boolean illustrative, @Nullable String note,
		Map<SkuCategory, List<Sku>> skus, Map<String, @Nullable Double> rates, @Nullable String sha256) {

	/**
	 * ArchSim's illustrative rates: roughly a large cloud's general-purpose VMs, no
	 * instance types.
	 */
	public static final PriceSnapshot GENERIC = generic();

	/** The provider of ArchSim's illustrative rates, which need no snapshot. */
	static final String GENERIC_PROVIDER = "generic";

	private static final String GENERIC_NOTE = "Illustrative on-demand rates, roughly a large cloud's general-purpose "
			+ "VMs; sizes are standard shapes (2, 4 or 8 GiB per vCPU), not real instance types.";

	/** The generic price of a vCPU per hour, in USD. */
	private static final double GENERIC_VCPU_HOUR = 0.037;

	/** The generic price of a GiB of memory per hour, in USD. */
	private static final double GENERIC_GIB_HOUR = 0.0028;

	/** The generic price of a GiB of storage per month, in USD. */
	private static final double GENERIC_STORAGE_GIB_MONTH = 0.08;

	/**
	 * Copies the collections, so the snapshot does not change.
	 */
	public PriceSnapshot {
		sources = List.copyOf(sources);
		Map<SkuCategory, List<Sku>> lists = new EnumMap<>(SkuCategory.class);
		skus.forEach((category, items) -> lists.put(category, List.copyOf(items)));
		skus = Collections.unmodifiableMap(lists);
		rates = Collections.unmodifiableMap(new LinkedHashMap<>(rates));
	}

	/**
	 * The snapshot a file's JSON holds.
	 * @param data the file's JSON
	 * @return the snapshot, with the hash of its content
	 * @throws PricingException naming the first thing wrong with it, when it is not a
	 * valid one
	 */
	public static PriceSnapshot parse(JsonNode data) {
		SnapshotFile file;
		try {
			file = RecordCodec.read(data, SnapshotFile.class);
		}
		catch (BindingException ex) {
			BindingError first = ex.errors().getFirst();
			throw new PricingException(
					"not a valid price snapshot (%s: %s)".formatted(first.location(), first.message()));
		}
		Map<SkuCategory, List<Sku>> skus = new EnumMap<>(SkuCategory.class);
		skus.put(SkuCategory.COMPUTE, file.compute());
		skus.put(SkuCategory.DATABASE, file.databases());
		skus.put(SkuCategory.CACHE, file.caches());
		skus.put(SkuCategory.STREAM, file.streams());
		Map<String, @Nullable Double> rates = new LinkedHashMap<>();
		RecordCodec.write(file.rates())
			.properties()
			.forEach((rate) -> rates.put(rate.getKey(), price(rate.getValue())));
		return new PriceSnapshot(file.provider(), file.region(), file.snapshotDate(), file.sources(), file.currency(),
				false, file.note(), skus, rates, contentSha256(data));
	}

	/**
	 * The hash of a snapshot's JSON content: of its canonical text, so the same prices
	 * give the same hash whatever the file's formatting or line endings.
	 * @param data the snapshot's JSON
	 * @return the SHA-256 hash, in lower-case hex
	 */
	public static String contentSha256(JsonNode data) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(CanonicalJson.write(data).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("every Java has SHA-256", ex);
		}
	}

	/**
	 * How messages name it: {@code aws us-east-1 (2026-10-28)}, or the generic rates.
	 * @return the name
	 */
	public String label() {
		return this.illustrative ? "generic (illustrative rates)"
				: "%s %s (%s)".formatted(this.provider, this.region, this.snapshotDate);
	}

	/**
	 * Whether it prices instance types (the generic rates do not).
	 * @return {@code true} when one of its price lists has an instance type
	 */
	public boolean hasShapes() {
		return this.skus.values().stream().anyMatch((items) -> !items.isEmpty());
	}

	/**
	 * Every instance family in its price lists, sorted.
	 * @return the families
	 */
	public Set<String> families() {
		Set<String> families = new TreeSet<>();
		this.skus.values().forEach((items) -> items.forEach((sku) -> families.add(sku.family())));
		return families;
	}

	/**
	 * Every instance type in its price lists.
	 * @return the instance types, sorted
	 */
	public Set<String> instanceTypes() {
		Set<String> types = new TreeSet<>();
		this.skus.values().forEach((items) -> items.forEach((sku) -> types.add(sku.sku())));
		return types;
	}

	/**
	 * The {@code families} that are in none of its price lists, in order (they restrict
	 * nothing).
	 * @param wanted the families a document names
	 * @return those of them it does not know
	 */
	public List<String> unknownFamilies(List<String> wanted) {
		Set<String> known = families();
		return wanted.stream().filter((family) -> !known.contains(family)).toList();
	}

	/**
	 * What it is, without its prices, as the Python engine's {@code Snapshot.to_json()}
	 * writes it.
	 * @return its provider, region, date, currency, sources, note and hash
	 */
	public ObjectNode toJson() {
		ObjectNode json = JsonNodeFactory.instance.objectNode();
		json.put("provider", this.provider);
		json.put("region", this.region);
		json.put("snapshotDate", this.snapshotDate);
		json.put("currency", this.currency);
		json.put("illustrative", this.illustrative);
		this.sources.stream().map(RecordCodec::write).forEach(json.putArray("sources")::add);
		json.put("note", this.note);
		json.put("sha256", this.sha256);
		return json;
	}

	private static PriceSnapshot generic() {
		Map<String, @Nullable Double> rates = new LinkedHashMap<>();
		rates.put("vcpuHour", GENERIC_VCPU_HOUR);
		rates.put("gibHour", GENERIC_GIB_HOUR);
		rates.put("storageGibMonth", GENERIC_STORAGE_GIB_MONTH);
		return new PriceSnapshot(GENERIC_PROVIDER, null, null, List.of(), "USD", true, GENERIC_NOTE, Map.of(), rates,
				null);
	}

	private static @Nullable Double price(JsonNode rate) {
		return rate.isNull() ? null : rate.doubleValue();
	}

}
