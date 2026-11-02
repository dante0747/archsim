package io.archsim.model.upgrade;

import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.text.PythonText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The upgrade chain from schema 1.0 to the current one (ADR-0023): one {@link Upgrade} per
 * version, applied in turn, as the Python engine's {@code migrate_document} does. Every step so
 * far only adds optional fields, so older documents read unchanged; the chain still runs, so a
 * future step that changes the JSON slots in.
 *
 * <p>A document newer than the current schema, or with no path to it, is refused with a message
 * saying why, rather than guessed at.
 */
public final class Upgrades {

    /** The chain, oldest first; a schema change adds a step at the end. */
    public static final List<Upgrade> CHAIN = List.of(
            Upgrade.additive(
                    "1.0",
                    "1.1",
                    "1.1 adds optional provenance and calibration, the empirical distribution and the trace"
                            + " traffic pattern"),
            Upgrade.additive("1.1", "1.2", "1.2 adds optional pricing (generic by default, as before)"),
            Upgrade.additive(
                    "1.2",
                    "1.3",
                    "1.3 adds the link settings readsOnMiss and continueOnFailure (cache-aside; off by default,"
                            + " as before) and lets a cache leave its misses to its callers"),
            Upgrade.additive(
                    "1.3",
                    "1.4",
                    "1.4 adds the optional part setting instanceType (the instance type a part runs on, kept"
                            + " while it covers the part's CPU and memory; empty: the cheapest that does, as before)"),
            Upgrade.additive(
                    "1.4",
                    "1.5",
                    "1.5 adds the service settings cpuShare (the share of service time that needs a CPU core;"
                            + " 1 by default, as before) and memoryPerRpsMb (memory that grows with throughput;"
                            + " 0 by default, as before)"),
            Upgrade.additive(
                    "1.5",
                    "1.6",
                    "1.6 adds the service setting serviceTimeByLoad (empty by default: the same service time"
                            + " at every load, as before) and the message-queue settings consumerConcurrency and"
                            + " fetchOverheadMs (empty by default: each message taken as soon as a consumer is"
                            + " free, as before)"),
            Upgrade.additive(
                    "1.6",
                    "1.7",
                    "1.7 adds architecture.host (empty by default: each part's vCPUs are its own, as before)"
                            + " and the service, database and cache setting siblingSpeed (empty by default: every"
                            + " vCPU a full core, as before)"));

    private static final String CURRENT = ArchitectureDocument.SCHEMA_VERSION;

    private Upgrades() {}

    /**
     * The document upgraded to the current schema.
     *
     * @throws UpgradeException when it cannot be
     */
    public static Upgraded toCurrent(JsonNode raw) {
        return upgrade(raw, CURRENT, CHAIN);
    }

    /**
     * The document upgraded to {@code target} along {@code chain}.
     *
     * @throws UpgradeException when it cannot be
     */
    public static Upgraded upgrade(JsonNode raw, String target, List<Upgrade> chain) {
        if (!(raw instanceof ObjectNode object)) {
            throw new UpgradeException("an architecture document must be a JSON object");
        }
        JsonNode stamped = object.get("schemaVersion");
        if (stamped == null || stamped.isNull()) {
            throw new UpgradeException("missing 'schemaVersion'; is this an ArchSim architecture document?");
        }
        String version = PythonText.str(stamped);
        if (SchemaVersion.parse(version).isNewerThan(SchemaVersion.parse(target))) {
            throw new UpgradeException(
                    ("this document uses schema %s, newer than the supported %s. " + "Upgrade ArchSim to open it.")
                            .formatted(version, target));
        }
        Map<String, Upgrade> steps = new LinkedHashMap<>();
        chain.forEach(step -> steps.put(step.from(), step));
        ObjectNode document = object.deepCopy();
        List<String> notes = new ArrayList<>();
        while (!version.equals(target)) {
            Upgrade step = steps.get(version);
            if (step == null || notes.size() > chain.size()) {
                throw new UpgradeException("no migration path from schema %s to %s".formatted(version, target));
            }
            document = step.apply().apply(document);
            document.put("schemaVersion", step.to());
            notes.add("migrated schema %s -> %s".formatted(version, step.to()));
            version = step.to();
        }
        return new Upgraded(document, notes);
    }
}
