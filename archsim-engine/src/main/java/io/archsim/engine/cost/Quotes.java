package io.archsim.engine.cost;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.pricing.InstanceTypes;
import io.archsim.engine.pricing.Machine;
import io.archsim.engine.pricing.MachineSize;
import io.archsim.engine.pricing.Purchase;
import io.archsim.engine.text.HalfUp;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.document.ProvenanceKind;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.SkuCategory;

/**
 * A part's price per instance-hour, the first that applies: a price a person set on the
 * part; with a price snapshot, the cheapest instance type that covers its CPU and memory,
 * plus its storage; a technology preset's illustrative price; its size at generic rates;
 * the catalog default. A price equal to the catalog default counts as not set, and a
 * preset's price is illustrative, so a snapshot reprices it.
 */
final class Quotes {

	/** The setting a price per instance-hour is set with. */
	static final String COST_PER_HOUR = "costPerHour";

	/** Prices keep four decimals. */
	private static final double TEN_THOUSANDTHS = 10_000;

	private final PriceSnapshot snapshot;

	private final String option;

	private final InstanceTypes types;

	private final Map<String, ProvenanceKind> provenance;

	Quotes(PriceSnapshot snapshot, Purchase purchase, Map<String, ProvenanceKind> provenance) {
		this.snapshot = snapshot;
		this.option = purchase.option();
		this.types = new InstanceTypes(snapshot, purchase);
		this.provenance = provenance;
	}

	/**
	 * The price of one instance-hour of a part.
	 * @param part the part
	 * @return the price and where it came from
	 */
	Quote quote(CostPart part) {
		PriceBasis origin = origin(part);
		if (origin == PriceBasis.SET) {
			return new Quote(part.writtenNumber(COST_PER_HOUR), PriceBasis.SET, null, null);
		}
		return sized(part, origin).orElseGet(() -> catalogDefault(part));
	}

	/**
	 * The price of one instance-hour of a part's size, even when a price is set on it.
	 * @param part the part
	 * @return the price of its instance type, else of its size at generic rates;
	 * {@link Quote#NONE} without a size
	 */
	Quote sizeQuote(CostPart part) {
		return sized(part, null).orElse(Quote.NONE);
	}

	/**
	 * The price of a part's size: the snapshot's cheapest instance type that covers it,
	 * else a preset's illustrative price, else its size at generic rates.
	 * @param part the part
	 * @param origin where its {@code costPerHour} came from; {@code null} for its size's
	 * price only
	 * @return the price; empty without a size
	 */
	private Optional<Quote> sized(CostPart part, @Nullable PriceBasis origin) {
		Sizes sizes = sizes(part);
		String note = null;
		if (this.snapshot.hasShapes() && (sizes.cpu() != null || sizes.memory() != null)) {
			Optional<Machine> machine = this.types.choose(part.type(), part.overDefaults(),
					new MachineSize(sizes.cpu(), sizes.memory(), null));
			Sku sku = machine.map(Machine::sku).orElse(null);
			if (machine.isPresent() && sku != null) {
				return Optional.of(snapshotQuote(machine.get(), sku, sizes.storage()));
			}
			note = machine
				.map((found) -> "no " + found.category().json() + " type in " + this.snapshot.label()
						+ " covers its size")
				.orElse(null);
		}
		return illustrative(part, origin, sizes, note);
	}

	/**
	 * Where a part's {@code costPerHour} came from: set by a person (any value whose
	 * provenance is measured or assumed), the catalog default, a preset of its technology
	 * (or marked illustrative), else set.
	 * @param part the part
	 * @return the origin; {@code null} when the part has no price
	 */
	@Nullable PriceBasis origin(CostPart part) {
		Double own = part.writtenNumber(COST_PER_HOUR);
		if (own == null) {
			return null;
		}
		ProvenanceKind kind = this.provenance.get("nodes/" + part.id() + "/" + COST_PER_HOUR);
		if (kind == ProvenanceKind.MEASURED || kind == ProvenanceKind.ASSUMED) {
			return PriceBasis.SET;
		}
		// compared with ==, as in Python, where -0.0 equals 0.0 and Double.equals does
		// not
		double price = own;
		Double fallback = part.defaultNumber(COST_PER_HOUR);
		if (fallback != null && fallback == price) {
			return PriceBasis.DEFAULT;
		}
		if (kind == ProvenanceKind.ILLUSTRATIVE || presetPrices(part).stream().anyMatch((preset) -> preset == price)) {
			return PriceBasis.PRESET;
		}
		return PriceBasis.SET;
	}

	/**
	 * The hourly price of one instance of a size at the generic illustrative rates.
	 * @param sizes the instance's CPU, memory and storage
	 * @return the price, to four decimals; {@code null} when CPU and memory are both
	 * unset
	 */
	static @Nullable Double sizePricePerHour(Sizes sizes) {
		if (sizes.cpu() == null && sizes.memory() == null) {
			return null;
		}
		double compute = orZero(sizes.cpu()) * GenericRates.VCPU_HOUR + orZero(sizes.memory()) * GenericRates.GIB_HOUR;
		double disk = orZero(sizes.storage()) * GenericRates.STORAGE_GIB_MONTH / CostEstimator.HOURS_PER_MONTH;
		return HalfUp.round((compute + disk) * TEN_THOUSANDTHS, 0) / TEN_THOUSANDTHS;
	}

	private Quote snapshotQuote(Machine machine, Sku sku, @Nullable Double storage) {
		double price = sku.price(this.option).orElse(0.0);
		Double rate = this.snapshot.rates().get("storageGibMonth");
		if (machine.category() == SkuCategory.DATABASE && !machine.selfManaged()) {
			// a managed database's storage has its own rate (RDS gp3)
			Double managed = this.snapshot.rates().get("rdsStorageGibMonth");
			rate = (managed != null && managed != 0) ? managed : rate;
		}
		if (storage != null && storage != 0 && rate != null) {
			price += storage * rate / CostEstimator.HOURS_PER_MONTH;
		}
		return new Quote(HalfUp.round(price * TEN_THOUSANDTHS, 0) / TEN_THOUSANDTHS, PriceBasis.SNAPSHOT, sku.sku(),
				machine.selfManaged() ? "self-managed on compute instances" : null);
	}

	/**
	 * An illustrative price when no snapshot type prices the part: its preset's, else its
	 * size's at generic rates.
	 * @param part the part
	 * @param origin where its own price came from
	 * @param sizes its CPU, memory and storage
	 * @param note why the snapshot did not price it, when it could not
	 * @return the price; empty when it has neither
	 */
	private static Optional<Quote> illustrative(CostPart part, @Nullable PriceBasis origin, Sizes sizes,
			@Nullable String note) {
		if (origin == PriceBasis.PRESET) {
			return Optional.of(new Quote(part.writtenNumber(COST_PER_HOUR), PriceBasis.PRESET, null,
					(note != null) ? note + ": priced at its preset's price" : null));
		}
		Double size = sizePricePerHour(sizes);
		return Optional.ofNullable(size)
			.map((price) -> new Quote(price, PriceBasis.SIZE, null,
					(note != null) ? note + ": priced at generic rates" : null));
	}

	/**
	 * The part's own price, else its type's default.
	 * @param part the part
	 * @return the price, as the catalog default; no price when neither has one
	 */
	private static Quote catalogDefault(CostPart part) {
		Double own = part.writtenNumber(COST_PER_HOUR);
		Double price = (own != null) ? own : part.defaultNumber(COST_PER_HOUR);
		return (price != null) ? new Quote(price, PriceBasis.DEFAULT, null, null) : Quote.NONE;
	}

	/**
	 * A part's instance size: a Kubernetes cluster's node, else the part's own specs.
	 * @param part the part
	 * @return its CPU and memory in effect, and its storage as written
	 */
	private static Sizes sizes(CostPart part) {
		boolean cluster = KubernetesClusterSpec.TYPE.equals(part.type());
		return new Sizes(part.valueNumber(cluster ? "nodeVcpu" : "cpuCores"),
				part.valueNumber(cluster ? "nodeMemoryGib" : "memoryGib"),
				cluster ? null : part.writtenNumber("storageGib"));
	}

	private static List<Double> presetPrices(CostPart part) {
		String technology = part.technology();
		return part.presets()
			.stream()
			.filter((preset) -> preset.technology().equals(technology))
			.map((preset) -> preset.setting(COST_PER_HOUR).map(CostPart::number).orElse(null))
			.filter(Objects::nonNull)
			.toList();
	}

	private static double orZero(@Nullable Double value) {
		return (value != null) ? value : 0.0;
	}

	/**
	 * An instance's size.
	 *
	 * @param cpu its vCPU
	 * @param memory its memory, in GiB
	 * @param storage its disk, in GiB
	 */
	record Sizes(@Nullable Double cpu, @Nullable Double memory, @Nullable Double storage) {
	}

}
