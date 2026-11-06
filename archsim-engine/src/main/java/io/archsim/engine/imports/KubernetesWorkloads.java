package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.archsim.engine.imports.Manifests.Workload;

/**
 * The workloads of Kubernetes manifests, read into parts one by one: each part's id by
 * its workload's name, the links each states, and which run on Kubernetes.
 */
final class KubernetesWorkloads {

	private final DesignBuilder design;

	private final ImportLog log;

	private final PartNames names;

	private final List<String> onKubernetes = new ArrayList<>();

	private final Set<String> tools = new HashSet<>();

	private final Set<String> unsetReplicas = new HashSet<>();

	/**
	 * Reads workloads into a design.
	 * @param design the design
	 * @param log the report
	 * @param names the parts by workload name, and the links parts state, which reading
	 * fills
	 */
	KubernetesWorkloads(DesignBuilder design, ImportLog log, PartNames names) {
		this.design = design;
		this.log = log;
		this.names = names;
	}

	/**
	 * The services that run on Kubernetes.
	 * @return them, which a cluster part holds
	 */
	List<String> onKubernetes() {
		return Collections.unmodifiableList(this.onKubernetes);
	}

	/**
	 * The parts whose image is a tool.
	 * @return them, never taken as the entry point
	 */
	Set<String> tools() {
		return Collections.unmodifiableSet(this.tools);
	}

	/**
	 * The parts whose replicas Kubernetes' default gives.
	 * @return them, whose replicas an autoscaler then sets
	 */
	Set<String> unsetReplicas() {
		return Collections.unmodifiableSet(this.unsetReplicas);
	}

	/**
	 * Reads one workload into a part.
	 * @param workload the workload
	 */
	void read(Workload workload) {
		KubernetesWorkloadReader reader = new KubernetesWorkloadReader(this.design, workload);
		reader.addTo(this.log);
		this.names.name(workload.name(), reader.nodeId());
		reader.statedCalls().ifPresent((calls) -> this.names.stateCalls(reader.nodeId(), calls));
		if (reader.isTool()) {
			this.tools.add(reader.nodeId());
		}
		if (reader.runsOnKubernetes()) {
			this.onKubernetes.add(reader.nodeId());
		}
		if (reader.replicasUnset()) {
			this.unsetReplicas.add(reader.nodeId());
		}
	}

}
