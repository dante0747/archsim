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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotWriterTests {

	/** The day the tests' snapshots were recorded on. */
	static final Clock NOVEMBER_14 = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

	private final StringWriter err = new StringWriter();

	@Test
	void write_everyListAndRates_writesWhatScriptsPricesWrote(@TempDir Path folder) throws IOException {
		Path out = folder.resolve("out.json");

		writer(folder).write(awsShaped(), Optional.of(out));

		assertThat(Files.readString(out, StandardCharsets.UTF_8))
			.isEqualTo(Recorded.text("aws-sample-1-2026-11-14.json"));
	}

	@Test
	void write_noFileGiven_writesItAmongTheSnapshotsByProviderRegionAndDate(@TempDir Path folder) throws IOException {
		Path written = writer(folder).write(awsShaped(), Optional.empty());

		assertThat(written).isEqualTo(folder.resolve("aws-sample-1-2026-11-14.json")).isRegularFile();
	}

	@Test
	void write_any_saysWhereAndHowManyTypesOfEachList(@TempDir Path folder) throws IOException {
		Path written = writer(folder).write(awsShaped(), Optional.empty());

		assertThat(this.err.toString().lines())
			.containsExactly("wrote " + written + " {'compute': 4, 'databases': 2, 'caches': 2, 'streams': 0}");
	}

	private SnapshotWriter writer(Path folder) {
		return new SnapshotWriter(folder, NOVEMBER_14, new PrintWriter(this.err, true));
	}

	// four compute types, a database type for two engines, a cache type at two prices
	private static Snapshot awsShaped() {
		Sku t4g = Sku.of("t4g.large", "t4g", Shape.of(2, 8), 0.0672).withBurst(true, 0.3).withCredit(0.04);
		List<Sku> compute = List.of(Sku.of("m7g.xlarge", "m7g", Shape.of(4, 16), 0.1632), t4g,
				Sku.of("m7g.large", "m7g", Shape.of(2, 8), 0.0816), Sku.of("c4.large", "c4", Shape.of(2, 3.75), 0.1));
		List<Sku> databases = List.of(engine(Sku.of("db.m7g.large", "m7g", Shape.of(2, 8), 0.168), "postgresql"),
				engine(Sku.of("db.m7g.large", "m7g", Shape.of(2, 8), 0.168), "mysql"),
				engine(Sku.of("db.t4g.micro", "t4g", Shape.of(2, 1), 0.016), "postgresql").withBurst(true, 0.1));
		List<Sku> caches = List.of(engine(Sku.of("cache.r7g.large", "r7g", Shape.of(2, 13.07), 0.219), "valkey"),
				engine(Sku.of("cache.r7g.large", "r7g", Shape.of(2, 13.07), 0.274), "redis"));
		SequencedMap<String, List<Sku>> lists = new LinkedHashMap<>();
		lists.put("compute", compute);
		lists.put("databases", databases);
		lists.put("caches", caches);
		lists.put("streams", List.of());
		SequencedMap<String, Double> rates = new LinkedHashMap<>();
		rates.put("storageGibMonth", 0.08);
		rates.put("serverlessPerMillionRequests", 0.2);
		rates.put("serverlessGbSecond", 1.66667e-05);
		return new Snapshot("aws", "sample-1",
				List.of(new Snapshot.Source("AWS Price List bulk API",
						"https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/index.json")),
				"Offer file versions: AmazonEC2 20261001000000. AWS: sample \u2014 \u00e9t\u00e9", lists,
				Optional.of(rates));
	}

	private static Sku engine(Sku sku, String engine) {
		return sku.withEngines(List.of(engine));
	}

}
