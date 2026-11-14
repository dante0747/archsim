package io.archsim.tools.prices;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A region's AWS snapshot from the bulk API's offer files: {@code sample-1}'s are the
 * parsers' samples, each offer at a version of its own.
 */
class AwsPricesTests {

	private static final String BASE = "https://pricing.us-east-1.amazonaws.com";

	private static final List<String> OFFERS = List.of("AmazonEC2", "AmazonRDS", "AmazonElastiCache", "AmazonMSK",
			"AWSLambda", "AWSQueueService", "AWSELB", "AmazonDynamoDB", "AmazonApiGateway", "AmazonEKS");

	@Test
	void snapshot_aRegionsOfferFiles_isWhatScriptsPricesWrote(@TempDir Path folder) throws IOException {
		Path out = folder.resolve("out.json");
		Snapshot snapshot = AwsPrices.snapshot("sample-1", downloads(), folder.resolve("cache"));

		new SnapshotWriter(folder, SnapshotWriterTests.NOVEMBER_14, new PrintWriter(new StringWriter())).write(snapshot,
				Optional.of(out));

		assertThat(Files.readString(out, StandardCharsets.UTF_8))
			.isEqualTo(Recorded.text("aws-offers-sample-1-2026-11-14.json"));
	}

	@Test
	void download_anOffer_keepsItsCurrentVersionInTheCache(@TempDir Path folder) throws IOException {
		Path file = AwsPrices.download(downloads(), "AmazonMSK", "sample-1", folder);

		assertThat(file).isEqualTo(folder.resolve("AmazonMSK-sample-1-20261013000000.csv"))
			.hasContent(Recorded.text("offers/AmazonMSK.csv"));
	}

	@Test
	void download_aRegionWithoutAPriceList_isRefused(@TempDir Path folder) {
		assertThatExceptionOfType(PriceListException.class)
			.isThrownBy(() -> AwsPrices.download(downloads(), "AmazonMSK", "nowhere-1", folder))
			.withMessage("AmazonMSK has no price list for region nowhere-1");
	}

	@Test
	void download_aRegionWithoutItsCurrentVersion_isRefused(@TempDir Path folder) {
		String index = BASE + "/offers/v1.0/aws/AmazonMSK/current/region_index.json";
		FakeDownloads downloads = new FakeDownloads(Map.of(index, """
				{"regions": {"sample-1": {"regionCode": "sample-1"}}}
				"""));

		assertThatExceptionOfType(PriceListException.class)
			.isThrownBy(() -> AwsPrices.download(downloads, "AmazonMSK", "sample-1", folder))
			.withMessage("AmazonMSK names no current version for region sample-1: "
					+ "the price list format may have changed");
	}

	/**
	 * The bulk API's region indexes and sample-1's offer files, as {@code aws.py} asked.
	 */
	static FakeDownloads downloads() {
		Map<String, String> pages = new HashMap<>();
		for (int index = 0; index < OFFERS.size(); index++) {
			String offer = OFFERS.get(index);
			String version = "202610" + (index + 10) + "000000";
			String path = "/offers/v1.0/aws/" + offer + "/" + version + "/sample-1/index";
			pages.put(BASE + "/offers/v1.0/aws/" + offer + "/current/region_index.json", """
					{"regions": {"sample-1": {"regionCode": "sample-1", "currentVersionUrl": "%s.json"}}}
					""".formatted(path));
			pages.put(BASE + path + ".csv", Recorded.text("offers/" + offer + ".csv"));
		}
		return new FakeDownloads(pages);
	}

}
