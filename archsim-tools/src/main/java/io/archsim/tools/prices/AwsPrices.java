package io.archsim.tools.prices;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;

/**
 * A region's AWS snapshot from the Price List bulk API (public, no account needed): EC2,
 * RDS, ElastiCache, MSK, EBS gp3, Lambda, SQS, the load balancers, DynamoDB, API Gateway
 * and the EKS control plane, read from each offer's CSV for the region. Only the region's
 * own current-generation prices are kept: a region's price list also carries Outposts and
 * Local Zone rows, Capacity Blocks and extended-support surcharges, which must never be
 * taken for its on-demand price.
 */
final class AwsPrices {

	/** The bulk API. */
	static final String BASE = "https://pricing.us-east-1.amazonaws.com";

	/**
	 * The offers a snapshot reads, in the order they are fetched and named in its note.
	 */
	static final List<String> OFFERS = List.of("AmazonEC2", "AmazonRDS", "AmazonElastiCache", "AmazonMSK", "AWSLambda",
			"AWSQueueService", "AWSELB", "AmazonDynamoDB", "AmazonApiGateway", "AmazonEKS");

	private static final Snapshot.Source SOURCE = new Snapshot.Source("AWS Price List bulk API",
			BASE + "/offers/v1.0/aws/index.json");

	/** How long a snapshot's note may be, in characters. */
	private static final int NOTE_LENGTH = 500;

	private AwsPrices() {
	}

	/**
	 * A region's snapshot: each offer's current CSV for the region, fetched into the
	 * cache unless it is there, then read.
	 * @param region the region, such as {@code us-east-1}
	 * @param downloads where the files come from
	 * @param cache the folder the files are kept in
	 * @return the snapshot
	 * @throws IOException when a file cannot be fetched or read
	 * @throws PriceListException when an offer has no price list for the region, or one
	 * does not read as expected
	 */
	static Snapshot snapshot(String region, PriceDownloads downloads, Path cache) throws IOException {
		SequencedMap<String, Path> files = new LinkedHashMap<>();
		for (String offer : OFFERS) {
			files.put(offer, download(downloads, offer, region, cache));
		}
		Ec2Prices.Ec2 ec2 = read(files, "AmazonEC2", Ec2Prices::read);
		String note = noteOf(files);
		SequencedMap<String, List<Sku>> lists = new LinkedHashMap<>();
		lists.put("compute", ec2.compute());
		lists.put("databases", read(files, "AmazonRDS", RdsPrices::databases));
		lists.put("caches", read(files, "AmazonElastiCache", ElastiCachePrices::caches));
		lists.put("streams", read(files, "AmazonMSK", (rows) -> MskPrices.streams(rows, ec2.shapes())));
		return new Snapshot("aws", region, List.of(SOURCE), note, lists, Optional.of(rates(files, ec2)));
	}

	/**
	 * An offer's current CSV for a region, cached by its version, so that a newer version
	 * is fetched and an unchanged one is not.
	 * @param downloads where the files come from
	 * @param offer the offer, such as {@code AmazonEC2}
	 * @param region the region
	 * @param cache the cache's folder
	 * @return the cached file, {@code <offer>-<region>-<version>.csv}
	 * @throws IOException when the region's index or the file cannot be fetched
	 * @throws PriceListException when the offer has no price list for the region, or
	 * names no current version of it
	 */
	static Path download(PriceDownloads downloads, String offer, String region, Path cache) throws IOException {
		URI index = URI.create(BASE + "/offers/v1.0/aws/" + offer + "/current/region_index.json");
		JsonNode regions = PythonJsonReader.decode(new String(downloads.bytes(index), StandardCharsets.UTF_8))
			.path("regions");
		JsonNode entry = regions.path(region);
		if (!JsonValues.isTrue(entry)) {
			throw new PriceListException(offer + " has no price list for region " + region);
		}
		// /offers/v1.0/aws/<offer>/<version>/<region>/index.json
		JsonNode current = entry.path("currentVersionUrl");
		if (!current.isString()) {
			throw new PriceListException(offer + " names no current version for region " + region
					+ ": the price list format may have changed");
		}
		String versionUrl = current.stringValue();
		String version = Arrays.stream(versionUrl.split("/", -1))
			.filter(OfferText::isDigits)
			.findFirst()
			.orElse("current");
		return downloads.file(URI.create(BASE + versionUrl.replace(".json", ".csv")),
				cache.resolve(offer + "-" + region + "-" + version + ".csv"));
	}

	/**
	 * A snapshot's note: which version of each offer file it was read from, and AWS's own
	 * disclaimer about the price list, cut at 500 characters.
	 * @param headers each offer file's metadata, by offer
	 * @return the note
	 */
	static String note(SequencedMap<String, ? extends Map<String, String>> headers) {
		String versions = headers.entrySet()
			.stream()
			.map((offer) -> offer.getKey() + " " + offer.getValue().getOrDefault("Version", "?"))
			.collect(Collectors.joining(", "));
		String disclaimer = headers.values()
			.stream()
			.map((header) -> header.getOrDefault("Disclaimer", ""))
			.filter((text) -> !text.isEmpty())
			.findFirst()
			.orElse("");
		int[] note = ("Offer file versions: " + versions + ". AWS: " + disclaimer).codePoints().toArray();
		return new String(note, 0, Math.min(note.length, NOTE_LENGTH));
	}

	private static String noteOf(SequencedMap<String, Path> files) throws IOException {
		SequencedMap<String, Map<String, String>> headers = new LinkedHashMap<>();
		for (Map.Entry<String, Path> file : files.entrySet()) {
			try (BufferedReader text = Files.newBufferedReader(file.getValue(), StandardCharsets.UTF_8)) {
				headers.put(file.getKey(), OfferFile.header(text));
			}
		}
		return note(headers);
	}

	// the rates in scripts/prices' order: EBS gp3, Lambda, SQS, the load balancers, RDS
	// storage, DynamoDB, API Gateway, EKS
	private static SequencedMap<String, Double> rates(SequencedMap<String, Path> files, Ec2Prices.Ec2 ec2)
			throws IOException {
		SequencedMap<String, Double> rates = new LinkedHashMap<>(UsageRates.rate("storageGibMonth", ec2.gp3()));
		try (Stream<OfferRow> lambda = OfferFile.rows(file(files, "AWSLambda"));
				Stream<OfferRow> sqs = OfferFile.rows(file(files, "AWSQueueService"));
				Stream<OfferRow> elb = OfferFile.rows(file(files, "AWSELB"))) {
			rates.putAll(UsageRates.rates(lambda, sqs, elb));
		}
		rates.putAll(read(files, "AmazonRDS", RdsPrices::storage));
		rates.putAll(read(files, "AmazonDynamoDB", UsageRates::dynamodb));
		rates.putAll(read(files, "AmazonApiGateway", UsageRates::apiGateway));
		rates.putAll(read(files, "AmazonEKS", UsageRates::eks));
		return rates;
	}

	private static <T> T read(SequencedMap<String, Path> files, String offer, Function<Stream<OfferRow>, T> reader)
			throws IOException {
		try (Stream<OfferRow> rows = OfferFile.rows(file(files, offer))) {
			return reader.apply(rows);
		}
	}

	private static Path file(SequencedMap<String, Path> files, String offer) {
		return Objects.requireNonNull(files.get(offer), offer);
	}

}
