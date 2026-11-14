package io.archsim.tools.prices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Stream;

import io.archsim.model.text.PythonText;

/**
 * EC2's prices from its offer file: the instance-hours of Linux on shared tenancy with no
 * preinstalled software, on demand; EBS gp3 storage per GiB-month; and the price of the
 * surplus CPU credits a burstable family spends in unlimited mode. GPU, machine-learning,
 * FPGA and media types are left out: never the cheapest fit for the CPU and memory a part
 * needs.
 */
final class Ec2Prices {

	/** EC2's instance categories (its {@code Instance Family} column) left out. */
	private static final Set<String> ACCELERATED = Set.of("GPU instance", "Machine Learning ASIC Instances",
			"FPGA Instances", "Media Accelerator Instances");

	private final List<Sku> compute = new ArrayList<>();

	private final Map<String, Shape> shapes = new HashMap<>();

	private final Map<String, Double> credits = new HashMap<>();

	private OptionalDouble gp3 = OptionalDouble.empty();

	private Ec2Prices() {
	}

	/**
	 * EC2's prices from its offer file's rows.
	 * @param rows the rows, in the file's order
	 * @return the instance types, their shapes by name, and the gp3 price
	 * @throws PriceListException when gp3 storage has two prices
	 */
	static Ec2 read(Stream<OfferRow> rows) {
		Ec2Prices prices = new Ec2Prices();
		rows.forEach(prices::add);
		List<Sku> compute = prices.compute.stream()
			.map((type) -> Burstables.withCredit(type, prices.credits.get(type.family())))
			.toList();
		return new Ec2(compute, Map.copyOf(prices.shapes), prices.gp3);
	}

	private void add(OfferRow row) {
		if (!row.isOnDemand()) {
			return;
		}
		if (isLinuxCredits(row)) {
			if (row.isRegional() && row.price() > 0) {
				this.credits.put(Burstables.creditFamily(row), row.price());
			}
		}
		else if (isGp3(row)) {
			if (row.isRegional() && row.price() > 0) {
				addGp3(row.price());
			}
		}
		else if (isInstanceHour(row)) {
			addInstance(row);
		}
	}

	private void addGp3(double price) {
		if (this.gp3.isPresent() && this.gp3.getAsDouble() != price) {
			throw new PriceListException("AmazonEC2: two gp3 prices, " + PythonText.repr(this.gp3.getAsDouble())
					+ " and " + PythonText.repr(price) + "; the price list format may have changed");
		}
		this.gp3 = OptionalDouble.of(price);
	}

	private void addInstance(OfferRow row) {
		String type = row.text("Instance Type");
		String vcpu = row.text("vCPU");
		Double memory = OfferText.gib(row.text("Memory"));
		double price = row.price();
		if (type.isEmpty() || !OfferText.isDigits(vcpu) || memory == null || price == 0) {
			return;
		}
		Shape shape = Shape.of(OfferText.vcpu(vcpu), memory);
		this.shapes.put(type, shape);
		this.compute.add(Sku.of(type, OfferText.part(type, 0), shape, price)
			.withBurst(Burstables.isBurstable(type), Burstables.baselineCpu(type)));
	}

	private static boolean isLinuxCredits(OfferRow row) {
		return row.is("Product Family", "CPU Credits") && row.is("Operating System", "Linux")
				&& row.is("Unit", "vCPU-Hours");
	}

	private static boolean isGp3(OfferRow row) {
		return row.is("Product Family", "Storage") && row.is("Volume API Name", "gp3") && row.is("Unit", "GB-Mo");
	}

	// the region's own on-demand instance-hour of Linux on shared tenancy, as is
	private static boolean isInstanceHour(OfferRow row) {
		return isRegionalInstance(row) && isPlainLinux(row) && row.is("CapacityStatus", "Used", "")
				&& row.is("Unit", "Hrs");
	}

	// not a Capacity Block's hour, nor an accelerated type's
	private static boolean isRegionalInstance(OfferRow row) {
		boolean onDemand = row.text("MarketOption").equals("OnDemand") || row.text("MarketOption").isEmpty();
		return row.is("Product Family", "Compute Instance") && row.isRegional() && onDemand
				&& !ACCELERATED.contains(row.text("Instance Family"));
	}

	private static boolean isPlainLinux(OfferRow row) {
		return row.is("Operating System", "Linux") && row.is("Tenancy", "Shared")
				&& row.is("Pre Installed S/W", "NA", "") && row.is("License Model", "No License required", "");
	}

	/**
	 * EC2's prices.
	 *
	 * @param compute the instance types, in the file's order, a burstable one with its
	 * family's credit price
	 * @param shapes each type's shape by its name, for the brokers MSK names alone
	 * @param gp3 gp3 storage's price per GiB-month, when the file has one
	 */
	record Ec2(List<Sku> compute, Map<String, Shape> shapes, OptionalDouble gp3) {

	}

}
