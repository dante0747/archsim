package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.archsim.engine.report.ReportNode;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.external.ExternalApiSpec;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.document.Workload;

/**
 * Findings about data stores and dependencies: hot shards, throttling, third parties'
 * errors and latency, read replicas, replication lag, lock contention, and a cluster's
 * pods waiting for room.
 */
final class DataFindings {

	/** The fewest operations (or calls) a part's figures are judged from. */
	static final long MIN_SAMPLES = 50;

	/** The decimals a share is cited with. */
	static final int SHARE_DECIMALS = 4;

	private DataFindings() {
	}

	/**
	 * The data and dependency findings of a run, part by part.
	 * @param run the run
	 * @return a cluster's pending pods; a data store's hot shard, read replicas,
	 * replication lag and lock contention; a third party's errors and latency; and any
	 * part's throttling
	 */
	static List<RunFinding> of(RunContext run) {
		List<RunFinding> findings = new ArrayList<>();
		for (ReportNode node : run.report().nodes()) {
			if (KubernetesClusterSpec.TYPE.equals(node.type())) {
				DependencyFindings.pendingPods(node, run).ifPresent(findings::add);
				continue;
			}
			if (DatabaseSpec.TYPE.equals(node.type())) {
				findings.addAll(database(node, run));
			}
			if (ExternalApiSpec.TYPE.equals(node.type())) {
				findings.addAll(DependencyFindings.thirdParty(node, run.report()));
			}
			DependencyFindings.throttling(node, run).ifPresent(findings::add);
		}
		return findings;
	}

	private static List<RunFinding> database(ReportNode node, RunContext run) {
		Optional<DatabaseConfig> config = run.config(node.id(), DatabaseConfig.class);
		Workload workload = run.resolved().workload();
		List<RunFinding> findings = new ArrayList<>();
		config.flatMap((settings) -> StoreFindings.hotShard(node, settings, workload)).ifPresent(findings::add);
		config.flatMap((settings) -> StoreFindings.readReplicas(node, settings)).ifPresent(findings::add);
		Integer applyWorkers = config.map(DatabaseConfig::replicaApplyWorkers).orElse(null);
		StoreFindings.replicationLag(node, applyWorkers).ifPresent(findings::add);
		StoreFindings.lockContention(node, workload).ifPresent(findings::add);
		return findings;
	}

}
