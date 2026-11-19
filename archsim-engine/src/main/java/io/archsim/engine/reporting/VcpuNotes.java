package io.archsim.engine.reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.host.SiblingSpeed;
import io.archsim.engine.host.VcpuBasis;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.Resources;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.NodeSpec;

/**
 * The model notes on how a run counted its parts' vCPUs (ADR-0091): the parts whose vCPUs
 * were hyperthread pairs by default, an assumption no measurement of ArchSim's stands
 * behind, and the pods, which count whole cores where an x86 node pool would give them
 * pairs.
 */
final class VcpuNotes {

	private VcpuNotes() {
	}

	/**
	 * The notes of a run.
	 * @param architecture the architecture the run simulated, in the order it names its
	 * parts
	 * @param speeds how each part's vCPUs counted, by its id
	 * @return a note naming the parts on the default and one naming the pods; none for
	 * either without such parts
	 */
	static List<String> of(Architecture architecture, Map<String, SiblingSpeed> speeds) {
		List<String> notes = new ArrayList<>();
		List<String> onTheDefault = named(architecture, speeds, VcpuBasis.DEFAULT);
		if (!onTheDefault.isEmpty()) {
			notes.add(onTheDefault(onTheDefault));
		}
		List<String> pods = named(architecture, speeds, VcpuBasis.POD);
		if (!pods.isEmpty()) {
			notes.add(pods(pods));
		}
		return List.copyOf(notes);
	}

	private static String onTheDefault(List<String> names) {
		double speed = Resources.DEFAULT_SIBLING_SPEED;
		return ("vCPUs counted as hyperthread pairs (%s): each of these parts counts its vCPUs as two per physical "
				+ "core, as on most x86 instances, a busy pair doing %s cores' work (the default sibling speed of %s, "
				+ "from vendors' documentation and published benchmarks, not a measurement of ArchSim's). Set "
				+ "siblingSpeed on a part, or on the design, where its hardware is known.")
			.formatted(PartNames.shown(names), NumberText.brief(2 * speed), NumberText.brief(speed));
	}

	private static String pods(List<String> names) {
		return ("Pods counted as whole cores (%s): each vCPU a pod requests counts as a core of its own, which is "
				+ "optimistic on an x86 node pool, where a vCPU is a hyperthread. Set siblingSpeed on the service to "
				+ "count its pods' vCPUs as pairs.")
			.formatted(PartNames.shown(names));
	}

	private static List<String> named(Architecture architecture, Map<String, SiblingSpeed> speeds, VcpuBasis basis) {
		return architecture.nodes()
			.stream()
			.filter((node) -> hasBasis(speeds.get(node.id()), basis))
			.map(NodeSpec::name)
			.toList();
	}

	private static boolean hasBasis(@Nullable SiblingSpeed speed, VcpuBasis basis) {
		return speed != null && speed.basis() == basis;
	}

}
