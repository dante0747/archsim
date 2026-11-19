package io.archsim.engine.sizing;

import java.util.Map;

import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.sizing.Placement.Work;
import io.archsim.engine.sizing.Vcpus.SizedVcpus;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.document.Workload;
import io.archsim.model.text.PythonFormat;

/**
 * The size of a database instance: CPU for its queries at the peak (each shard's primary
 * takes its reads and every write; a replica set or ring spreads them over its nodes),
 * capped at what its connections keep busy; memory for its hot data in its buffer cache
 * and for its connections; disk for its data, kept under 70% full.
 */
final class DatabaseSizer {

	private static final double BYTES_PER_GIB = 1L << 30;

	private static final double MILLIS_PER_SECOND = 1000;

	/** An item's size in bytes per KB: data sizes count 1,000 bytes to the KB. */
	private static final double BYTES_PER_KB = 1000;

	private static final double TOLERANCE = 1e-9;

	private DatabaseSizer() {
	}

	/**
	 * The size of one instance.
	 * @param config the database's settings
	 * @param load the reads and writes it carries at the peak
	 * @param workload the design's workload, for its key count
	 * @param placement where it runs
	 * @return its size
	 */
	static PartSize size(DatabaseConfig config, NodeLoad load, Workload workload, Placement placement) {
		Work work = (config.replicationFactor() > 1) ? replicated(config, load) : primary(config, load);
		Work atSpeed = placement.atSpeed(work.busy(), work.intro());
		int connections = Math.max(1, config.maxConnections());
		int usable = Math.max(1, (int) Math.ceil(connections * config.cpuShare() - TOLERANCE));
		SizedVcpus sized = placement.vcpus()
			.forWork(atSpeed, usable,
					"%d connection%s × %s CPU keep at most %d core%s busy (add connections, read replicas or shards)"
						.formatted(connections, SizingText.plural(connections), SizingText.percent(config.cpuShare()),
								usable, SizingText.plural(usable)));
		CpuSize cpu = sized.chosen();
		Data data = Data.of(config, workload);
		Memory memory = Memory.of(config, data, connections);
		Map<Need, Double> need = Map.of(Need.BUSY_CORES, atSpeed.busy(), Need.CPU_CORES, cpu.needed(), Need.MEMORY_GIB,
				memory.needGib(), Need.STORAGE_GIB, data.perInstanceGib());
		return new PartSize(cpu.vcpu(), Steps.memory(memory.needGib(), Sizing.DB_MIN_MEMORY_GIB),
				Steps.disk(data.perInstanceGib() / Sizing.STORAGE_FILL), 0.0,
				Map.of(Spec.CPU_CORES, cpu.why(), Spec.MEMORY_GIB, memory.why(), Spec.STORAGE_GIB, data.why()), null,
				null, need, null, false, cpu.siblingSpeed(), sized.kinds());
	}

	/**
	 * The work of a ring or replica set: every write goes to every replica, a read to as
	 * many as its consistency needs, spread over all the nodes.
	 * @param config the database's settings
	 * @param load the reads and writes it carries
	 * @return the cores each node keeps busy, and how
	 */
	private static Work replicated(DatabaseConfig config, NodeLoad load) {
		int copies = config.replicationFactor();
		int asked = switch (config.consistency()) {
			case ALL -> copies;
			case QUORUM -> copies / 2 + 1;
			case ONE -> 1;
		};
		int nodes = Math.max(1, config.shards()) * Math.max(copies, 1 + Math.max(0, config.readReplicas()));
		double readMs = config.readServiceTimeMs().expected();
		double writeMs = config.writeServiceTimeMs().expected();
		double busy = (load.read() * readMs * asked + load.write() * writeMs * copies) * config.cpuShare()
				/ MILLIS_PER_SECOND / nodes;
		String intro = ("%s reads/s × %d replica%s × %s ms + %s writes/s × %d replicas × %s ms, %s of it CPU, "
				+ "over %d nodes")
			.formatted(SizingText.brief(load.read()), asked, SizingText.plural(asked), SizingText.brief(readMs),
					SizingText.brief(load.write()), copies, SizingText.brief(writeMs),
					SizingText.percent(config.cpuShare()), nodes);
		return new Work(busy, intro);
	}

	/**
	 * The work of each shard's primary: its share of the reads, over its read replicas,
	 * and every write to its shard; a scattered read (a search engine searches every
	 * shard) reaches every shard.
	 * @param config the database's settings
	 * @param load the reads and writes it carries
	 * @return the cores each primary keeps busy, and how
	 */
	private static Work primary(DatabaseConfig config, NodeLoad load) {
		int shards = Math.max(1, config.shards());
		int copies = 1 + Math.max(0, config.readReplicas());
		double scatter = (shards > 1) ? scatter(config) : 0.0;
		double readsPerShard = load.read() * (scatter + (1 - scatter) / shards);
		double readMs = config.readServiceTimeMs().expected();
		double writeMs = config.writeServiceTimeMs().expected();
		double busy = ((readsPerShard / copies) * readMs + (load.write() / shards) * writeMs) * config.cpuShare()
				/ MILLIS_PER_SECOND;
		String scattered = "";
		if (scatter > 0) {
			String what = (scatter >= 1) ? "every read searches all"
					: SizingText.percent(scatter) + " of reads search all";
			scattered = " (" + what + " " + shards + " shards)";
		}
		String intro = "Each primary: %s reads/s%s × %s ms + %s writes/s × %s ms, %s of it CPU".formatted(
				SizingText.brief(readsPerShard / copies), scattered, SizingText.brief(readMs),
				SizingText.brief(load.write() / shards), SizingText.brief(writeMs),
				SizingText.percent(config.cpuShare()));
		return new Work(busy, intro);
	}

	/**
	 * The share of reads sent to every shard: set on the database, else its technology's.
	 * @param config the database's settings
	 * @return the share, from 0 to 1
	 */
	private static double scatter(DatabaseConfig config) {
		double share = config.profile().number("scatterReads", config.scatterReads()).orElse(0.0);
		return Math.min(1.0, Math.max(0.0, share));
	}

	/**
	 * The data a database stores.
	 *
	 * @param perInstanceGib the data on each instance (each shard), in GiB
	 * @param why how the disk was worked out
	 */
	private record Data(double perInstanceGib, String why) {

		static Data of(DatabaseConfig config, Workload workload) {
			int shards = Math.max(1, config.shards());
			Double given = config.dataSizeGib();
			double data = (given != null) ? given
					: workload.keyCount() * config.itemSizeKb() * BYTES_PER_KB / BYTES_PER_GIB;
			String source = (given != null) ? SizingText.gib(data) + " of data"
					: "%s keys × %s KB ≈ %s (set the data size for a better estimate)".formatted(
							SizingText.brief(workload.keyCount()), SizingText.brief(config.itemSizeKb()),
							SizingText.gib(data));
			String over = (shards > 1) ? " over " + shards + " shards" : "";
			String why = "%s%s, %s (at least %s GiB)".formatted(source, over, SizingText.fill(),
					SizingText.plain(Sizing.MIN_STORAGE_GIB));
			return new Data(data / shards, why);
		}

	}

	/**
	 * A database's memory: its hot data in a buffer cache of 75% of memory (the working
	 * set, else an illustrative share of the data it stores), and enough for its
	 * connections (RDS's default {@code max_connections} formula).
	 *
	 * @param needGib the memory it needs, in GiB
	 * @param why how it was worked out
	 */
	private record Memory(double needGib, String why) {

		static Memory of(DatabaseConfig config, Data data, int connections) {
			Double workingSet = config.workingSetGib();
			double hot = (workingSet != null) ? workingSet / Math.max(1, config.shards())
					: Sizing.DB_HOT_SHARE * data.perInstanceGib();
			double forConnections = connections * Sizing.DB_BYTES_PER_CONNECTION / BYTES_PER_GIB;
			String floor = ", at least %s for %d connections (RDS's default: %s bytes each) and %s GiB in all"
				.formatted(SizingText.gib(forConnections), connections,
						PythonFormat.format(Sizing.DB_BYTES_PER_CONNECTION, ","),
						SizingText.plain(Sizing.DB_MIN_MEMORY_GIB));
			String cache = SizingText.percent(DatabaseConfig.BUFFER_CACHE_SHARE);
			String why = (workingSet != null)
					? "%s of hot data per instance in a buffer cache of %s of memory, so reads stay in memory%s"
						.formatted(SizingText.gib(hot), cache, floor)
					: ("%s of hot data per instance (%s of the %s it stores, illustrative: set a hot working set for a "
							+ "better estimate) in a buffer cache of %s of memory%s")
						.formatted(SizingText.gib(hot), SizingText.percent(Sizing.DB_HOT_SHARE),
								SizingText.gib(data.perInstanceGib()), cache, floor);
			return new Memory(Math.max(hot / DatabaseConfig.BUFFER_CACHE_SHARE, forConnections), why);
		}

	}

}
