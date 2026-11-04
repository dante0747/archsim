package io.archsim.engine.pricing;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.document.Burstable;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.SkuCategory;

/**
 * The instance types a design may buy from a price snapshot: the cheapest that covers a
 * size, and the one a part runs on.
 */
public final class InstanceTypes {

	/**
	 * Burstable families auto-sizing may pick by itself (Graviton t4g: EC2, RDS db.t4g
	 * and ElastiCache cache.t4g, the cheapest of their kind); others only when the design
	 * names them.
	 */
	public static final Set<String> AUTO_BURSTABLE = Set.of("t4g");

	private final PriceSnapshot snapshot;

	private final Purchase purchase;

	/**
	 * The types of a snapshot, bought as a design buys them.
	 * @param snapshot the price snapshot
	 * @param purchase the design's purchase option, families and burstable policy
	 */
	public InstanceTypes(PriceSnapshot snapshot, Purchase purchase) {
		this.snapshot = snapshot;
		this.purchase = purchase;
	}

	/**
	 * The cheapest type of a price list that covers a size and runs the engine. The
	 * families the design names restrict a price list that has any of their non-burstable
	 * families (naming compute families leaves the database list alone); a burstable
	 * family named there is allowed wherever it fits, and restricts nothing. A t4g type
	 * also, with burstable on auto, no named family restricting the list, and the part
	 * passing the credit check. Ties go to the smaller machine, then the name.
	 * @param kind the price list and engine
	 * @param size the size to cover
	 * @return the type; empty when none fits
	 */
	public Optional<Sku> cover(MachineKind kind, MachineSize size) {
		List<Sku> items = items(kind.category());
		Set<String> named = Set.copyOf(this.purchase.families());
		Set<String> restricting = namedFamilies(items, named, (sku) -> !sku.burstable());
		Set<String> namedBursts = namedFamilies(items, named, Sku::burstable);
		String option = this.purchase.option();
		return items.stream()
			.filter((sku) -> size.fitsIn(sku.vcpu(), sku.memoryGib()) && sku.price(option).isPresent())
			.filter((sku) -> allowed(sku, restricting, namedBursts, size.busy()))
			.filter((sku) -> sku.runs(kind.engine()))
			.min(Comparator.<Sku>comparingDouble((sku) -> sku.price(option).orElseThrow())
				.thenComparingDouble(Sku::vcpu)
				.thenComparingDouble(Sku::memoryGib)
				.thenComparing(Sku::sku));
	}

	/**
	 * The instance type a part of a size runs on: its own {@code instanceType} while the
	 * snapshot has it and it covers the size, else the cheapest that does. A database,
	 * cache or stream engine the snapshot has no managed type for runs on compute
	 * instances (self-managed).
	 * @param nodeType the part's component type
	 * @param settings the part's settings
	 * @param size the size to cover
	 * @return the machine; empty when the part is not billed per machine or the snapshot
	 * has no machine shapes
	 */
	public Optional<Machine> choose(String nodeType, LenientSettings settings, MachineSize size) {
		Optional<MachineKind> kind = MachineKind.of(nodeType, settings);
		if (kind.isEmpty() || !this.snapshot.hasShapes()) {
			return Optional.empty();
		}
		Optional<String> named = settings.text("instanceType").filter((name) -> !name.isEmpty());
		if (named.isPresent()) {
			Optional<Machine> own = named(kind.get(), named.get(), size);
			if (own.isPresent()) {
				return own;
			}
		}
		return Optional.of(cheapest(kind.get(), size));
	}

	private Optional<Machine> named(MachineKind kind, String name, MachineSize size) {
		Optional<Sku> own = find(kind.category(), name, kind.engine());
		Optional<Sku> sku = (own.isEmpty() && !kind.isCompute()) ? find(SkuCategory.COMPUTE, name, null) : own;
		if (sku.isEmpty() || !size.fitsIn(sku.get().vcpu(), sku.get().memoryGib())
				|| sku.get().price(this.purchase.option()).isEmpty()) {
			return Optional.empty();
		}
		if (own.isPresent()) {
			return Optional.of(new Machine(kind.category(), kind.engine(), sku.get(), false, true));
		}
		return Optional.of(new Machine(SkuCategory.COMPUTE, kind.engine(), sku.get(), true, true));
	}

	private Machine cheapest(MachineKind kind, MachineSize size) {
		Optional<Sku> sku = cover(kind, size);
		String engine = kind.engine();
		boolean engineOffered = engine != null
				&& items(kind.category()).stream().anyMatch((item) -> item.engines().contains(engine));
		if (sku.isEmpty() && !kind.isCompute() && !engineOffered) {
			Sku onCompute = cover(new MachineKind(SkuCategory.COMPUTE, null), size).orElse(null);
			return new Machine(SkuCategory.COMPUTE, kind.engine(), onCompute, true, false);
		}
		return new Machine(kind.category(), kind.engine(), sku.orElse(null), false, false);
	}

	/**
	 * The type of a price list with a name, that runs an engine.
	 * @param category the price list
	 * @param name the type's name
	 * @param engine the engine, or {@code null} for any
	 * @return the type, if the list has it
	 */
	private Optional<Sku> find(SkuCategory category, String name, @Nullable String engine) {
		return items(category).stream().filter((sku) -> sku.sku().equals(name) && sku.runs(engine)).findFirst();
	}

	private boolean allowed(Sku sku, Set<String> restricting, Set<String> namedBursts, @Nullable Busy busy) {
		if (!sku.burstable()) {
			return restricting.isEmpty() || restricting.contains(sku.family());
		}
		if (this.purchase.burstable() == Burstable.NEVER) {
			return false;
		}
		return namedBursts.contains(sku.family()) || autoBurstable(sku, restricting, busy);
	}

	/**
	 * Whether auto-sizing may pick a burstable type by itself: a family it picks on its
	 * own, no named family restricting the list, and a part that runs on the type's
	 * credits.
	 * @param sku the burstable type
	 * @param restricting the named families that restrict the list
	 * @param busy the cores one instance keeps busy, or {@code null} when unknown
	 * @return whether the type may be picked
	 */
	private static boolean autoBurstable(Sku sku, Set<String> restricting, @Nullable Busy busy) {
		return AUTO_BURSTABLE.contains(sku.family()) && restricting.isEmpty() && busy != null
				&& busy.runsOnCredits(sku);
	}

	private List<Sku> items(SkuCategory category) {
		return this.snapshot.skus().getOrDefault(category, List.of());
	}

	/**
	 * The families of a price list's types of a kind that the design names.
	 * @param items the price list's types
	 * @param named the families the design names
	 * @param kind which types: burstable or not
	 * @return the families
	 */
	private static Set<String> namedFamilies(List<Sku> items, Set<String> named, Predicate<Sku> kind) {
		return items.stream()
			.filter((sku) -> kind.test(sku) && named.contains(sku.family()))
			.map(Sku::family)
			.collect(Collectors.toSet());
	}

}
