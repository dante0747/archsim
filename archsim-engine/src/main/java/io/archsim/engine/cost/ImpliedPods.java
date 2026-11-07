package io.archsim.engine.cost;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.SkuCategory;

/**
 * Where the pods of a service on Kubernetes with no cluster in the design run: on implied
 * nodes, the type whose allocatable CPU and memory fit one pod at the lowest price per
 * pod. Burstable, HPC and bare-metal types are not node pools.
 *
 * @param sku the node type
 * @param share the share of a node one pod takes
 * @param perHour the price per pod-hour
 */
record ImpliedPods(Sku sku, double share, double perHour) {

	private static final double TOLERANCE = 1e-9;

	/** Ties in price per pod go to the smaller node, then the type's name. */
	private static final Comparator<ImpliedPods> CHEAPEST = Comparator.comparingDouble(ImpliedPods::perHour)
		.thenComparingDouble((pods) -> pods.sku().vcpu())
		.thenComparing((pods) -> pods.sku().sku());

	/**
	 * The implied nodes of a part.
	 * @param part the part
	 * @param snapshot the price snapshot
	 * @param option the purchase option
	 * @return its pods' nodes; empty for a part that is not a service on Kubernetes with
	 * a size, or a snapshot without instance types
	 */
	static Optional<ImpliedPods> of(CostPart part, PriceSnapshot snapshot, String option) {
		if (!ServiceSpec.TYPE.equals(part.type()) || !part.writtenIs("platform", "kubernetes")
				|| !snapshot.hasShapes()) {
			return Optional.empty();
		}
		Double cpu = part.writtenNumber("cpuCores");
		Double memory = part.writtenNumber("memoryGib");
		if (cpu == null && memory == null) {
			return Optional.empty();
		}
		List<Sku> compute = snapshot.skus().getOrDefault(SkuCategory.COMPUTE, List.of());
		return compute.stream()
			.map((sku) -> fit(sku, orZero(cpu), orZero(memory), option))
			.filter(Objects::nonNull)
			.min(CHEAPEST);
	}

	/**
	 * One pod on a node type.
	 * @param sku the node type
	 * @param cpu the pod's vCPU
	 * @param memory the pod's memory, in GiB
	 * @param option the purchase option
	 * @return the pod's share and price; {@code null} when the type is not a node pool or
	 * the pod does not fit
	 */
	private static @Nullable ImpliedPods fit(Sku sku, double cpu, double memory, String option) {
		Double price = sku.price(option).orElse(null);
		if (!nodePool(sku) || price == null || price == 0) {
			return null;
		}
		double allocatableCpu = sku.vcpu() * CostEstimator.KUBERNETES_ALLOCATABLE;
		double allocatableMemory = sku.memoryGib() * CostEstimator.KUBERNETES_ALLOCATABLE;
		if (allocatableCpu < cpu - TOLERANCE || allocatableMemory < memory - TOLERANCE) {
			return null;
		}
		double share = Math.max(cpu / allocatableCpu, memory / allocatableMemory);
		return new ImpliedPods(sku, share, share * price);
	}

	/**
	 * Whether a type can be a node pool: not burstable, HPC or bare metal.
	 * @param sku the type
	 * @return whether pods can run on it
	 */
	private static boolean nodePool(Sku sku) {
		return !sku.burstable() && !sku.family().startsWith("hpc") && !sku.sku().contains(".metal");
	}

	private static double orZero(@Nullable Double value) {
		return (value != null) ? value : 0.0;
	}

}
