package io.archsim.tools.prices;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The AWS offer files' parsers on {@code test_price_refresh_scripts.py}'s samples, which
 * copy the rows that tripped the first run against the live price lists: Outposts and
 * Capacity Block rows, previous generations, MSK's own column names, free and volume
 * tiers, a trust store's hours.
 */
class AwsOffersTests {

	@Test
	void ec2_offerRows_keepTheRegionsOwnOnDemandPricesAndMarkBurstableTypes() {
		Ec2Prices.Ec2 ec2 = Ec2Prices.read(offer("AmazonEC2"));

		assertThat(ec2.compute()).extracting(Sku::sku, Sku::hourly, (type) -> type.burst().burstable())
			.containsExactly(tuple("x9.large", 0.1, false), tuple("x9.2xlarge", 0.4, false),
					tuple("t9g.large", 0.04, true), tuple("t4g.large", 0.0672, true));
	}

	// the baseline from AWS's tables for the families it covers, and surplus credits at
	// the Linux price on that family's types only
	@Test
	void ec2_burstableTypes_carryTheirBaselineAndCreditPrice() {
		Ec2Prices.Ec2 ec2 = Ec2Prices.read(offer("AmazonEC2"));

		assertThat(ec2.compute()).extracting((type) -> type.burst().baselineCpu())
			.containsExactly(null, null, null, 0.3);
		assertThat(ec2.compute()).extracting((type) -> type.burst().creditVcpuHour())
			.containsExactly(null, null, null, 0.04);
	}

	@Test
	void ec2_offerRows_giveEachTypesShapeAndTheGp3Price() {
		Ec2Prices.Ec2 ec2 = Ec2Prices.read(offer("AmazonEC2"));

		assertThat(ec2.shapes()).containsEntry("x9.large", Shape.of(2, 8));
		assertThat(ec2.gp3()).hasValue(0.09);
	}

	@Test
	void ec2_twoGp3Prices_isRefused() {
		String twice = Recorded.text("offers/AmazonEC2.csv")
			.replace("\"Storage\",\"AWS Outposts\"", "\"Storage\",\"AWS Region\"");

		assertThatExceptionOfType(PriceListException.class).isThrownBy(() -> Ec2Prices.read(rows(twice)))
			.withMessage("AmazonEC2: two gp3 prices, 0.09 and 0.03; the price list format may have changed");
	}

	@Test
	void baselineCpu_types_isAwssTableForItsFamiliesAlone() {
		assertThat(Arrays.asList(Burstables.baselineCpu("db.t4g.micro"), Burstables.baselineCpu("cache.t3.small"),
				Burstables.baselineCpu("t2.micro"), Burstables.baselineCpu("m7g.large")))
			.containsExactly(0.1, 0.2, null, null);
	}

	@Test
	void databases_offerRows_keepSingleAzPricesForEachEngine() {
		List<Sku> databases = RdsPrices.databases(offer("AmazonRDS"));

		assertThat(databases).extracting(Sku::sku, (type) -> type.engines().getFirst(), Sku::hourly)
			.containsExactlyInAnyOrder(tuple("db.y9.large", "postgresql", 0.25), tuple("db.y9.large", "mysql", 0.24),
					tuple("db.y9.large", "mariadb", 0.24), tuple("db.t4g.micro", "postgresql", 0.016));
		assertThat(databases).filteredOn((type) -> type.sku().equals("db.t4g.micro"))
			.extracting((type) -> type.burst().baselineCpu(), (type) -> type.burst().creditVcpuHour())
			.containsExactly(tuple(0.1, 0.075));
	}

	@Test
	void caches_offerRows_leaveOutOutpostsAndExtendedSupport() {
		List<Sku> caches = ElastiCachePrices.caches(offer("AmazonElastiCache"));

		assertThat(caches).extracting((type) -> type.engines().getFirst(), Sku::hourly)
			.containsExactlyInAnyOrder(tuple("redis", 0.156), tuple("memcached", 0.156));
	}

	// MSK names a broker by its compute family, in hours; no Express or serverless
	@Test
	void streams_offerRows_nameStandardBrokersAsAwsDoes() {
		Map<String, Shape> shapes = Ec2Prices.read(offer("AmazonEC2")).shapes();

		List<Sku> streams = MskPrices.streams(offer("AmazonMSK"), shapes);

		assertThat(streams).extracting(Sku::sku, Sku::shape, (type) -> type.burst().burstable())
			.containsExactly(tuple("kafka.x9.large", Shape.of(2, 8), false),
					tuple("kafka.t9.small", Shape.of(2, 2), true));
	}

	// the first paid tier, not the free or the cheaper high-volume ones; a load
	// balancer's
	// hour, not a trust store's; capacity units used, not reserved; no gateway balancers
	@Test
	void rates_lambdaSqsAndElb_areEachRatesFirstPaidTier() {
		Map<String, Double> rates = UsageRates.rates(offer("AWSLambda"), offer("AWSQueueService"), offer("AWSELB"));

		assertThat(rates).containsExactly(entry("serverlessGbSecond", 0.0000166667),
				entry("serverlessPerMillionRequests", 0.2), entry("queuePerMillionRequests", 0.4),
				entry("loadBalancerHour", 0.0225), entry("loadBalancerLcuHour", 0.008),
				entry("networkLoadBalancerHour", 0.0225), entry("networkLoadBalancerLcuHour", 0.006));
	}

	// gp3 for a Single-AZ instance, not Multi-AZ or provisioned IOPS
	@Test
	void storage_rdsOfferRows_isSingleAzGp3() {
		assertThat(RdsPrices.storage(offer("AmazonRDS"))).containsExactly(entry("rdsStorageGibMonth", 0.115));
	}

	// the Standard table class, the paid tier past the free allowance; not PITR or IA
	// storage
	@Test
	void dynamodb_offerRows_areTheStandardTableClasssFirstPaidTiers() {
		assertThat(UsageRates.dynamodb(offer("AmazonDynamoDB"))).containsExactly(entry("dynamodbRcuHour", 0.00013),
				entry("dynamodbWcuHour", 0.00065), entry("dynamodbReadPerMillion", 0.125),
				entry("dynamodbWritePerMillion", 0.625), entry("dynamodbStorageGibMonth", 0.25));
	}

	// REST API requests' first tier, not HTTP APIs or WebSocket messages
	@Test
	void apiGateway_offerRows_isRestRequestsFirstTier() {
		assertThat(UsageRates.apiGateway(offer("AmazonApiGateway")))
			.containsExactly(entry("apiGatewayPerMillion", 3.5));
	}

	// the standard control plane, not extended support, provisioned tiers or Auto Mode
	@Test
	void eks_offerRows_isTheStandardControlPlanesHour() {
		assertThat(UsageRates.eks(offer("AmazonEKS"))).containsExactly(entry("kubernetesControlPlaneHour", 0.1));
	}

	@Test
	void rows_noHeaderRow_isRefused() {
		assertThatExceptionOfType(PriceListException.class).isThrownBy(() -> rows("not a price list\n"))
			.withMessage("no header row (SKU, ...) found: the price list format may have changed");
	}

	@Test
	void header_anOfferFile_isItsMetadata() {
		Map<String, String> header = OfferFile.header(reader(Recorded.text("offers/AmazonMSK.csv")));

		assertThat(header).containsExactly(entry("FormatVersion", "v1.0"), entry("Disclaimer", "sample"),
				entry("Publication Date", "2026-10-01T00:00:00Z"), entry("Version", "20261013000000"),
				entry("OfferCode", "AmazonMSK"));
	}

	/** An offer's sample file's rows. */
	static Stream<OfferRow> offer(String offer) {
		return rows(Recorded.text("offers/" + offer + ".csv"));
	}

	private static Stream<OfferRow> rows(String text) {
		return OfferFile.rows(reader(text));
	}

	private static BufferedReader reader(String text) {
		return new BufferedReader(new StringReader(text));
	}

}
