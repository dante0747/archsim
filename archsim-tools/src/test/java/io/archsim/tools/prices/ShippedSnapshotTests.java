package io.archsim.tools.prices;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AWS snapshot archsim-model ships, which {@code scripts/prices/aws.py} wrote: read
 * back into types and written again, it is the same file, byte for byte.
 */
class ShippedSnapshotTests {

	private static final Path ROOT = Path.of(System.getProperty("archsim.repoRoot", ".."));

	private static final Clock OCTOBER_28 = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void write_theSnapshotArchsimModelShips_writesItAgainByteForByte(@TempDir Path folder) throws IOException {
		Path shipped = ROOT.resolve(PricesCommand.SNAPSHOTS).resolve("aws-us-east-1-2026-10-28.json");
		String text = Files.readString(shipped, StandardCharsets.UTF_8);
		Path out = folder.resolve("out.json");

		new SnapshotWriter(folder, OCTOBER_28, new PrintWriter(new StringWriter()))
			.write(snapshot(PythonJsonReader.read(text)), Optional.of(out));

		assertThat(Files.readString(out, StandardCharsets.UTF_8)).isEqualTo(text);
	}

	private static Snapshot snapshot(JsonNode json) {
		SequencedMap<String, List<Sku>> lists = new LinkedHashMap<>();
		SnapshotWriter.LISTS.forEach((list) -> lists.put(list, types(json.path(list))));
		SequencedMap<String, Double> rates = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> rate : json.path("rates").properties()) {
			rates.put(rate.getKey(), rate.getValue().doubleValue());
		}
		JsonNode source = json.path("sources").path(0);
		return new Snapshot(json.path("provider").stringValue(), json.path("region").stringValue(),
				List.of(new Snapshot.Source(source.path("name").stringValue(), source.path("url").stringValue())),
				json.path("note").stringValue(), lists, Optional.of(rates));
	}

	private static List<Sku> types(JsonNode list) {
		List<Sku> types = new ArrayList<>();
		for (JsonNode type : list) {
			Shape shape = Shape.of(type.path("vcpu").doubleValue(), type.path("memoryGib").doubleValue());
			List<String> engines = new ArrayList<>();
			type.path("engines").forEach((engine) -> engines.add(engine.stringValue()));
			Double baseline = type.has("baselineCpu") ? type.path("baselineCpu").doubleValue() : null;
			Sku sku = Sku
				.of(type.path("sku").stringValue(), type.path("family").stringValue(), shape,
						type.path("hourly").path("on_demand").doubleValue())
				.withEngines(engines)
				.withBurst(type.path("burstable").asBoolean(false), baseline);
			types.add(type.has("creditVcpuHour") ? sku.withCredit(type.path("creditVcpuHour").doubleValue()) : sku);
		}
		return types;
	}

}
