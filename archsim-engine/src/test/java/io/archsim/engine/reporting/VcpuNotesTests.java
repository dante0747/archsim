package io.archsim.engine.reporting;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.host.SiblingSpeed;
import io.archsim.engine.host.VcpuBasis;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.NodeSpec;

import static org.assertj.core.api.Assertions.assertThat;

/** The model notes on how a run counted its parts' vCPUs (ADR-0091). */
class VcpuNotesTests {

	private static final Architecture PARTS = new Architecture(
			List.of(node("api", "API"), node("db", ""), node("cache", ""), node("worker", "")), List.of(), true, null,
			null);

	@Test
	void of_partsOnTheDefault_namesThemAndTheAssumption() {
		List<String> notes = VcpuNotes.of(PARTS,
				Map.of("api", pairs(), "db", pairs(), "cache", new SiblingSpeed(1.0, VcpuBasis.WHOLE_CORE_FAMILY)));

		assertThat(notes).containsExactly("vCPUs counted as hyperthread pairs (API, db): each of these parts counts "
				+ "its vCPUs as two per physical core, as on most x86 instances, a busy pair doing 1.2 cores' work "
				+ "(the default sibling speed of 0.6, from vendors' documentation and published benchmarks, not a "
				+ "measurement of ArchSim's). Set siblingSpeed on a part, or on the design, where its hardware is "
				+ "known.");
	}

	@Test
	void of_pods_namesThemAsOptimisticOnAnX86NodePool() {
		List<String> notes = VcpuNotes.of(PARTS, Map.of("worker", new SiblingSpeed(1.0, VcpuBasis.POD)));

		assertThat(notes).containsExactly("Pods counted as whole cores (worker): each vCPU a pod requests counts as a "
				+ "core of its own, which is optimistic on an x86 node pool, where a vCPU is a hyperthread. Set "
				+ "siblingSpeed on the service to count its pods' vCPUs as pairs.");
	}

	@Test
	void of_partsThatSetTheirSpeedOrThatTheDesignSets_noNote() {
		List<String> notes = VcpuNotes.of(PARTS,
				Map.of("api", new SiblingSpeed(0.6, VcpuBasis.PART), "db", new SiblingSpeed(1.0, VcpuBasis.DESIGN)));

		assertThat(notes).isEmpty();
	}

	@Test
	void of_partsOnTheDefaultAndPods_namesBothInTurn() {
		List<String> notes = VcpuNotes.of(PARTS, Map.of("db", pairs(), "worker", new SiblingSpeed(1.0, VcpuBasis.POD)));

		assertThat(notes).hasSize(2).first().asString().startsWith("vCPUs counted as hyperthread pairs (db)");
	}

	private static SiblingSpeed pairs() {
		return new SiblingSpeed(0.6, VcpuBasis.DEFAULT);
	}

	private static NodeSpec node(String id, String label) {
		return new NodeSpec(id, "service", label, null, JsonNodeFactory.instance.objectNode());
	}

}
