package io.archsim.engine.cost;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.HalfUp;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;

/**
 * Services on Kubernetes: their pods on a cluster in the design, which bills for them, or
 * on implied nodes ({@link ImpliedPods}) with a managed control plane.
 */
final class KubernetesLines {

	/** A pod's price per hour is rounded half up to four places. */
	private static final double FOUR_PLACES = 10_000;

	private KubernetesLines() {
	}

	/**
	 * The clusters in a design.
	 * @param document the design
	 * @return their ids, in the design's order
	 */
	static List<String> clusterIds(ArchitectureDocument document) {
		return document.architecture()
			.nodes()
			.stream()
			.filter((node) -> KubernetesClusterSpec.TYPE.equals(node.type()))
			.map(NodeSpec::id)
			.toList();
	}

	/**
	 * The cluster a part runs on.
	 * @param part a part
	 * @param clusterIds the design's clusters
	 * @return the cluster it names, else the only cluster in the design; {@code null} for
	 * a part that does not run on one
	 */
	static @Nullable String clusterOf(CostPart part, List<String> clusterIds) {
		if (!ServiceSpec.TYPE.equals(part.type()) || !part.writtenIs("platform", "kubernetes")
				|| clusterIds.isEmpty()) {
			return null;
		}
		JsonNode named = part.written("cluster");
		if (named.isString() && !named.stringValue().isEmpty()) {
			return clusterIds.contains(named.stringValue()) ? named.stringValue() : null;
		}
		return (clusterIds.size() == 1) ? clusterIds.get(0) : null;
	}

	/**
	 * A service's pods on a cluster in the design: the cluster's line bills for them.
	 * @param part the service
	 * @param cluster how the design names the cluster
	 * @return its line, free
	 */
	static CostLine onCluster(CostPart part, String cluster) {
		return new CostLine(part.id(), part.label(), part.type(), part.technology(), 0, null, null, null, CostUnit.HOUR,
				0.0, "runs on " + cluster, null, null, null, null, null);
	}

	/**
	 * A service's pods on implied nodes.
	 * @param part the service
	 * @param pods the node type its pods run on
	 * @param count its pods
	 * @param snapshotDate the date of the snapshot that prices the nodes
	 * @return its line
	 */
	static CostLine onPods(CostPart part, ImpliedPods pods, InstanceCount count, @Nullable String snapshotDate) {
		double unitPrice = HalfUp.round(pods.perHour() * FOUR_PLACES, 0) / FOUR_PLACES;
		String basis = count.basis().replace("replica", "pod") + " on implied " + pods.sku().sku()
				+ " nodes (no cluster in the design): " + HalfUp.fixed(pods.share(), 2) + " of a node each, "
				+ CostText.percent(CostEstimator.KUBERNETES_ALLOCATABLE) + " of a node allocatable";
		return new CostLine(part.id(), part.label(), part.type(), part.technology(), count.count(), null, unitPrice,
				PriceBasis.SNAPSHOT, CostUnit.HOUR, pods.perHour() * count.count() * CostEstimator.HOURS_PER_MONTH,
				basis, null, null, pods.sku().sku(), snapshotDate, null);
	}

	/**
	 * The managed control plane the pods on implied nodes need.
	 * @param plane its price per hour
	 * @param snapshotDate the date of the snapshot that prices it
	 * @return its line
	 */
	static CostLine controlPlane(double plane, @Nullable String snapshotDate) {
		return new CostLine("kubernetes-control-plane", "Kubernetes control plane", KubernetesClusterSpec.TYPE, null, 1,
				null, plane, PriceBasis.SNAPSHOT, CostUnit.HOUR, plane * CostEstimator.HOURS_PER_MONTH,
				"1 managed control plane, for the pods without a cluster in the design", null, null, null, snapshotDate,
				List.of(CostItem.of("control plane-hours", CostEstimator.HOURS_PER_MONTH, plane, "hour")));
	}

}
