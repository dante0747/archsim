package io.archsim.engine.sizing;

import java.util.Map;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.text.HalfUp;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.text.PythonFormat;

/**
 * The size of a serverless function, which runs one request per instance. Its CPU comes
 * with its memory (AWS Lambda: 1 vCPU per 1,769 MB): a full vCPU, so the request runs at
 * full speed, takes 1,769 MB; memory set on the function sets the CPU instead.
 */
final class FunctionSizer {

	private static final double MIB_PER_GIB = 1024;

	/** The least auto memory of a function instance, in GiB. */
	private static final double MIN_MEMORY_GIB = 0.125;

	/** The memory that comes with one vCPU is rounded to this many decimals of a GiB. */
	private static final int COUPLED_DECIMALS = 6;

	private static final String COUPLING = "a function gets 1 vCPU per "
			+ PythonFormat.format(Sizing.LAMBDA_MB_PER_VCPU, ",") + " MB of memory (AWS Lambda)";

	private FunctionSizer() {
	}

	/**
	 * The size of one function instance.
	 * @param config the function's settings
	 * @return its size
	 */
	static PartSize size(ServiceConfig config) {
		double mb = config.memoryBaselineMb() + config.memoryPerRequestMb();
		double full = Sizing.LAMBDA_MB_PER_VCPU / MIB_PER_GIB;
		Double setCpu = config.cpuCores();
		Double setMemory = config.memoryGib();
		double cpu = 1.0;
		String cpuWhy = "One request per instance: 1 vCPU runs it at full speed";
		if (setMemory != null && setCpu == null) {
			cpu = Math.min(1.0, setMemory / full);
			cpuWhy = memoryCpu(setMemory, cpu);
		}
		double vcpu = (setCpu != null) ? setCpu : cpu;
		double own = Steps.memory(mb * Sizing.MEMORY_HEADROOM / MIB_PER_GIB, MIN_MEMORY_GIB);
		double coupled = PythonRound.round(Math.min(1.0, vcpu) * full, COUPLED_DECIMALS);
		String memoryWhy = "%s MiB base + 1 request × %s MiB, %s".formatted(SizingText.brief(config.memoryBaselineMb()),
				SizingText.brief(config.memoryPerRequestMb()), SizingText.headroom());
		if (coupled >= own) {
			memoryWhy += "; %s, so %s vCPU takes %s MB".formatted(COUPLING, SizingText.plain(Math.min(1.0, vcpu)),
					HalfUp.grouped(coupled * MIB_PER_GIB));
		}
		Map<Need, Double> need = Map.of(Need.CPU_CORES, 1.0, Need.MEMORY_GIB, mb / MIB_PER_GIB, Need.MEMORY_GIB_MAX,
				mb / MIB_PER_GIB);
		return new PartSize(cpu, Math.max(own, coupled), null, 0.0,
				Map.of(Spec.CPU_CORES, cpuWhy, Spec.MEMORY_GIB, memoryWhy), null, null, need, null, false, null, null);
	}

	private static String memoryCpu(double memoryGib, double cpu) {
		String slower = (cpu < 1) ? "; below 1 its work runs slower" : "";
		return "%s GiB of memory: %s, so %s vCPU%s".formatted(SizingText.plain(memoryGib), COUPLING,
				SizingText.brief(cpu), slower);
	}

}
