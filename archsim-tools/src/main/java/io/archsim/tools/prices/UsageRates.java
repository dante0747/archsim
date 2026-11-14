package io.archsim.tools.prices;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonText;

/**
 * AWS's per-request and per-unit rates: Lambda, SQS, the load balancers, DynamoDB, API
 * Gateway and the EKS control plane. Each is the region's first paid tier: not the free
 * tier, and not the cheaper tiers for very high volumes.
 */
final class UsageRates {

	private static final double MILLION = 1e6;

	private static final int DECIMALS = 6;

	/**
	 * The load balancer-hour and capacity unit-hour of each kind, by family, usage and
	 * unit.
	 */
	private static final Map<List<String>, String> BALANCERS = Map.of(
			List.of("Load Balancer-Application", "LoadBalancerUsage", "Hrs"), "loadBalancerHour",
			List.of("Load Balancer-Application", "LCUUsage", "LCU-Hrs"), "loadBalancerLcuHour",
			List.of("Load Balancer-Network", "LoadBalancerUsage", "Hrs"), "networkLoadBalancerHour",
			List.of("Load Balancer-Network", "LCUUsage", "LCU-Hrs"), "networkLoadBalancerLcuHour");

	/** DynamoDB's request units and capacity unit-hours, by group and unit. */
	private static final Map<List<String>, String> DYNAMODB = Map.of(List.of("DDB-ReadUnits", "ReadRequestUnits"),
			"dynamodbReadPerMillion", List.of("DDB-WriteUnits", "WriteRequestUnits"), "dynamodbWritePerMillion",
			List.of("DDB-ReadUnits", "ReadCapacityUnit-Hrs"), "dynamodbRcuHour",
			List.of("DDB-WriteUnits", "WriteCapacityUnit-Hrs"), "dynamodbWcuHour");

	private UsageRates() {
	}

	/**
	 * Lambda's GB-second and requests, SQS's standard requests, and the application and
	 * network load balancers' hours and capacity unit-hours: a balancer's own hour, not a
	 * trust store's or an Outposts one's, and capacity used, not reserved.
	 * @param lambda the offer rows of Lambda
	 * @param sqs the offer rows of SQS
	 * @param elb the load balancers' offer rows
	 * @return the rates, in the order they were first found
	 * @throws PriceListException when a balancer's rate has several prices
	 */
	static SequencedMap<String, Double> rates(Stream<OfferRow> lambda, Stream<OfferRow> sqs, Stream<OfferRow> elb) {
		SequencedMap<String, Double> rates = new LinkedHashMap<>();
		lambda.filter(UsageRates::isFirstPaidTier).forEach((row) -> {
			if (row.is("Group", "AWS-Lambda-Duration") && row.is("Unit", "Lambda-GB-Second")) {
				rates.put("serverlessGbSecond", row.price());
			}
			else if (row.is("Group", "AWS-Lambda-Requests") && row.is("Unit", "Requests")) {
				rates.put("serverlessPerMillionRequests", perMillion(row.price()));
			}
		});
		sqs.filter(UsageRates::isFirstPaidTier)
			.filter((row) -> row.is("Queue Type", "Standard") && row.is("Unit", "Requests"))
			.forEach((row) -> rates.put("queuePerMillionRequests", perMillion(row.price())));
		SequencedMap<String, Set<Double>> found = new LinkedHashMap<>();
		elb.forEach((row) -> balancer(row)
			.ifPresent((key) -> found.computeIfAbsent(key, (name) -> new LinkedHashSet<>()).add(row.price())));
		found.forEach((key, prices) -> rates.putAll(rate(key, only(prices, "AWSELB " + key))));
		return rates;
	}

	/**
	 * DynamoDB's Standard table class, not Infrequent Access: on-demand read and write
	 * request units (per million), provisioned read and write capacity unit-hours, and
	 * table storage per GiB-month, each past its free allowance.
	 * @param rows the offer rows of DynamoDB
	 * @return the rates, in the order they were first found
	 * @throws PriceListException when a rate's first paid tier has several prices
	 */
	static SequencedMap<String, Double> dynamodb(Stream<OfferRow> rows) {
		SequencedMap<String, List<OfferRow>> found = new LinkedHashMap<>();
		rows.filter((row) -> row.isOnDemand() && row.isRegional())
			.forEach((row) -> dynamodbRate(row)
				.ifPresent((key) -> found.computeIfAbsent(key, (name) -> new ArrayList<>()).add(row)));
		SequencedMap<String, Double> rates = new LinkedHashMap<>();
		found.forEach((key, candidates) -> {
			OptionalDouble price = paidTier(candidates, "AmazonDynamoDB " + key);
			rates.putAll(rate(key, key.endsWith("PerMillion") ? perMillion(price) : price));
		});
		return rates;
	}

	/**
	 * API Gateway's REST API requests, first tier, per million. HTTP APIs and WebSocket
	 * messages are other, cheaper products, and a gateway cache is billed per hour on
	 * top; none of them is kept.
	 * @param rows the offer rows of API Gateway
	 * @return {@code apiGatewayPerMillion}, or no rate
	 * @throws PriceListException when the first tier has several prices
	 */
	static SequencedMap<String, Double> apiGateway(Stream<OfferRow> rows) {
		List<OfferRow> candidates = rows.filter((row) -> row.isOnDemand() && row.isRegional())
			.filter((row) -> row.is("Product Family", "API Calls") && "ApiGatewayRequest".equals(row.usage())
					&& row.is("Unit", "Requests"))
			.toList();
		return rate("apiGatewayPerMillion", perMillion(paidTier(candidates, "AmazonApiGateway REST API requests")));
	}

	/**
	 * The EKS control plane per cluster-hour on standard support: not extended support,
	 * the provisioned control-plane tiers, Auto Mode or Fargate.
	 * @param rows the offer rows of EKS
	 * @return {@code kubernetesControlPlaneHour}, or no rate
	 * @throws PriceListException when it has several prices
	 */
	static SequencedMap<String, Double> eks(Stream<OfferRow> rows) {
		Set<Double> prices = new LinkedHashSet<>();
		rows.filter((row) -> row.isOnDemand() && row.isRegional() && row.price() > 0)
			.filter((row) -> "AmazonEKS-Hours:perCluster".equals(row.usage()))
			.forEach((row) -> prices.add(row.price()));
		return rate("kubernetesControlPlaneHour", only(prices, "AmazonEKS cluster-hour"));
	}

	/**
	 * A rate, when it has a price.
	 * @param name the rate's name
	 * @param price its price
	 * @return the rate alone, or no rate
	 */
	static SequencedMap<String, Double> rate(String name, OptionalDouble price) {
		SequencedMap<String, Double> rate = new LinkedHashMap<>();
		price.ifPresent((value) -> rate.put(name, value));
		return rate;
	}

	/**
	 * A rate's one price.
	 * @param prices the prices its rows gave
	 * @param what the rate, for a message
	 * @return the price; empty for none
	 * @throws PriceListException when there are several
	 */
	static OptionalDouble only(Collection<Double> prices, String what) {
		if (prices.size() > 1) {
			String sorted = prices.stream().sorted().map(PythonText::repr).collect(Collectors.joining(", "));
			throw new PriceListException(
					what + ": several prices " + '[' + sorted + ']' + "; the price list format may have changed");
		}
		return prices.stream().mapToDouble(Double::doubleValue).findFirst();
	}

	// the first priced tier, from zero: not the free tier (no price) nor a cheaper
	// high-volume one
	private static boolean isFirstPaidTier(OfferRow row) {
		String start = row.text("StartingRange");
		boolean fromZero = start.isEmpty() || "0".equals(start);
		return row.isOnDemand() && row.isRegional() && fromZero && row.price() > 0;
	}

	// the lowest starting range with a price above zero: a free allowance can be a row of
	// its
	// own from zero, with the paid tier starting where it ends
	private static OptionalDouble paidTier(List<OfferRow> candidates, String what) {
		List<OfferRow> priced = candidates.stream().filter((row) -> row.price() > 0).toList();
		OptionalDouble start = priced.stream().mapToDouble(UsageRates::startingRange).min();
		if (start.isEmpty()) {
			return OptionalDouble.empty();
		}
		Set<Double> prices = new LinkedHashSet<>();
		priced.stream()
			.filter((row) -> startingRange(row) == start.getAsDouble())
			.forEach((row) -> prices.add(row.price()));
		return only(prices, what);
	}

	private static double startingRange(OfferRow row) {
		String start = row.text("StartingRange");
		return start.isEmpty() ? 0 : PythonNumbers.parseFloat(start);
	}

	// a balancer's rate: the hour itself ("LoadBalancerUsage", "USW2-LoadBalancerUsage"),
	// not a trust store's ("TS-") or an Outposts one
	private static Optional<String> balancer(OfferRow row) {
		List<String> usage = List.of(row.rawUsage().split("-", -1));
		String key = BALANCERS.get(List.of(row.text("Product Family"), usage.getLast(), row.text("Unit")));
		boolean own = !usage.contains("TS") && !usage.contains("Outposts");
		boolean priced = row.isOnDemand() && row.isRegional() && row.price() > 0;
		return Optional.ofNullable((own && priced) ? key : null);
	}

	private static Optional<String> dynamodbRate(OfferRow row) {
		String key = DYNAMODB.get(List.of(row.text("Group"), row.text("Unit")));
		boolean storage = row.is("Product Family", "Database Storage") && "TimedStorage-ByteHrs".equals(row.usage())
				&& row.is("Unit", "GB-Mo");
		if (key == null && storage) {
			key = "dynamodbStorageGibMonth";
		}
		return Optional.ofNullable(key);
	}

	private static double perMillion(double price) {
		return PythonRound.round(price * MILLION, DECIMALS);
	}

	private static OptionalDouble perMillion(OptionalDouble price) {
		return price.isPresent() ? OptionalDouble.of(perMillion(price.getAsDouble())) : price;
	}

}
