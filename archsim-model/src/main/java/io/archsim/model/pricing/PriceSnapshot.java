package io.archsim.model.pricing;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.text.CanonicalJson;
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

/**
 * The on-demand prices of one provider and region on one day, and the instance types they price;
 * or ArchSim's generic illustrative rates ({@link #GENERIC}), which price no instance types.
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
public record PriceSnapshot(
        String provider,
        @Nullable String region,
        @Nullable String snapshotDate,
        List<PriceSource> sources,
        String currency,
        boolean illustrative,
        @Nullable String note,
        Map<SkuCategory, List<Sku>> skus,
        Map<String, @Nullable Double> rates,
        @Nullable String sha256) {

    /** ArchSim's illustrative rates: roughly a large cloud's general-purpose VMs, no instance types. */
    public static final PriceSnapshot GENERIC = new PriceSnapshot(
            "generic",
            null,
            null,
            List.of(),
            "USD",
            true,
            "Illustrative on-demand rates, roughly a large cloud's general-purpose VMs; sizes are standard shapes"
                    + " (2, 4 or 8 GiB per vCPU), not real instance types.",
            Map.of(),
            genericRates(),
            null);

    public PriceSnapshot {
        sources = List.copyOf(sources);
        Map<SkuCategory, List<Sku>> lists = new EnumMap<>(SkuCategory.class);
        skus.forEach((category, items) -> lists.put(category, List.copyOf(items)));
        skus = Collections.unmodifiableMap(lists);
        rates = Collections.unmodifiableMap(new LinkedHashMap<>(rates));
    }

    private static Map<String, @Nullable Double> genericRates() {
        Map<String, @Nullable Double> rates = new LinkedHashMap<>();
        rates.put("vcpuHour", 0.037);
        rates.put("gibHour", 0.0028);
        rates.put("storageGibMonth", 0.08);
        return rates;
    }

    /**
     * The snapshot a file's JSON holds.
     *
     * @throws PricingException naming the first thing wrong with it, when it is not a valid one
     */
    public static PriceSnapshot parse(JsonNode data) {
        SnapshotFile file;
        try {
            file = RecordCodec.read(data, SnapshotFile.class);
        } catch (BindingException e) {
            BindingError first = e.errors().getFirst();
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
                .forEach(e -> rates.put(
                        e.getKey(), e.getValue().isNull() ? null : e.getValue().doubleValue()));
        return new PriceSnapshot(
                file.provider(),
                file.region(),
                file.snapshotDate(),
                file.sources(),
                file.currency(),
                false,
                file.note(),
                skus,
                rates,
                contentSha256(data));
    }

    /**
     * The hash of a snapshot's JSON content: of its canonical text, so the same prices give the
     * same hash whatever the file's formatting or line endings.
     */
    public static String contentSha256(JsonNode data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(CanonicalJson.write(data).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    /** How messages name it: {@code aws us-east-1 (2026-10-28)}, or the generic rates. */
    public String label() {
        return illustrative ? "generic (illustrative rates)" : "%s %s (%s)".formatted(provider, region, snapshotDate);
    }

    /** Whether it prices instance types (the generic rates do not). */
    public boolean hasShapes() {
        return skus.values().stream().anyMatch(items -> !items.isEmpty());
    }

    /** Every instance family in its price lists, sorted. */
    public Set<String> families() {
        Set<String> families = new TreeSet<>();
        skus.values().forEach(items -> items.forEach(s -> families.add(s.family())));
        return families;
    }

    /** Every instance type in its price lists. */
    public Set<String> instanceTypes() {
        Set<String> types = new TreeSet<>();
        skus.values().forEach(items -> items.forEach(s -> types.add(s.sku())));
        return types;
    }

    /** The {@code families} that are in none of its price lists, in order (they restrict nothing). */
    public List<String> unknownFamilies(List<String> wanted) {
        Set<String> known = families();
        return wanted.stream().filter(f -> !known.contains(f)).toList();
    }

    /** What it is, without its prices, as the Python engine's {@code Snapshot.to_json()} writes it. */
    public ObjectNode toJson() {
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        json.put("provider", provider);
        json.put("region", region);
        json.put("snapshotDate", snapshotDate);
        json.put("currency", currency);
        json.put("illustrative", illustrative);
        sources.stream().map(RecordCodec::write).forEach(json.putArray("sources")::add);
        json.put("note", note);
        json.put("sha256", sha256);
        return json;
    }
}
