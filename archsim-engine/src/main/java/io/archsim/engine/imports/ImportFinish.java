package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.roundtrip.ExportedLink;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.text.PythonFormat;

/**
 * What every import does last, so that its design validates and runs: memory limits a
 * service could hold no request in, and cache misses routed to a database. The import
 * then adds the clients ({@link EntryPoint}), unless the file carries its own.
 */
final class ImportFinish {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** MiB in a GiB. */
	private static final double MIB_PER_GIB = 1024;

	private final DesignBuilder design;

	private final ImportLog log;

	/**
	 * Finishes a design.
	 * @param design the design built
	 * @param log the report, which the guesses go into
	 */
	ImportFinish(DesignBuilder design, ImportLog log) {
		this.design = design;
		this.log = log;
	}

	/**
	 * Fits memory limits and routes cache misses.
	 */
	void finish() {
		fitMemory();
		wireCacheMisses();
	}

	/**
	 * A service whose memory limit is below ArchSim's default memory at idle (plus one
	 * request) could hold no request, which makes the design invalid. Real processes do
	 * run in such limits (a 128 MB function, a 256M container), so the idle figure is
	 * lowered to half the limit, noted, and listed as a guess.
	 */
	private void fitMemory() {
		LenientSettings defaults = LenientSettings
			.overDefaults(this.design.registry().find(ServiceSpec.TYPE).orElseThrow(), NODES.objectNode());
		for (ObjectNode node : this.design.nodes()) {
			ObjectNode config = (ObjectNode) node.path("config");
			JsonNode memory = config.path("memoryGib");
			if (!node.path("type").stringValue().equals(ServiceSpec.TYPE) || config.has("memoryBaselineMb")
					|| !memory.isNumber() || memory.doubleValue() <= 0) {
				continue;
			}
			double baseline = defaults.number("memoryBaselineMb").orElseThrow();
			double perRequest = config.has("memoryPerRequestMb")
					? PythonValues.toFloat(config.path("memoryPerRequestMb"))
					: defaults.number("memoryPerRequestMb").orElseThrow();
			double mib = memory.doubleValue() * MIB_PER_GIB;
			if (mib - baseline < perRequest) {
				lowerBaseline(node, mib, baseline);
			}
		}
	}

	private void lowerBaseline(ObjectNode node, double mib, double baseline) {
		long lowered = (long) Math.floor(mib / 2);
		((ObjectNode) node.path("config")).put("memoryBaselineMb", lowered);
		String nodeId = node.path("id").stringValue();
		String limit = PythonFormat.format(mib, "g");
		String idle = PythonFormat.format(baseline, "g");
		this.design.note(nodeId, "memoryBaselineMb", "half the " + limit + " MiB memory limit: ArchSim's default of "
				+ idle + " MiB at idle does not fit in it");
		this.log.guessed(nodeId + ": memory at idle " + lowered + " MiB, half its " + limit
				+ " MiB limit (ArchSim's default of " + idle + " MiB does not fit); set the real figure");
	}

	/**
	 * Who reads the data on a cache miss (a guess). When every service that uses a cache
	 * also calls one database, they do it themselves (cache-aside): their links to it get
	 * reads only on a miss. Otherwise the cache's misses go to the database its callers
	 * use, when there is one, or to the design's only database (read-through). A cache
	 * whose misses go nowhere makes the design invalid.
	 */
	private void wireCacheMisses() {
		List<String> databases = this.design.nodes()
			.stream()
			.filter((node) -> isType(node, "database"))
			.map((node) -> node.path("id").stringValue())
			.toList();
		for (ObjectNode cache : List.copyOf(this.design.nodes())) {
			String cacheId = cache.path("id").stringValue();
			if (!isType(cache, "cache") || "cdn".equals(cache.path("config").path("technology").asString(null))
					|| !this.design.callees(cacheId, "miss").isEmpty()) {
				continue;
			}
			List<String> callers = this.design.callers(cacheId);
			List<ObjectNode> databaseLinks = databaseLinks(callers);
			if (databaseLinks.stream()
				.anyMatch((link) -> PythonValues.truthy(link.path("config").path("readsOnMiss")))) {
				continue;
			}
			if (isCacheAside(callers, databaseLinks)) {
				readOnMiss(cacheId, databaseLinks);
			}
			else {
				missTo(cacheId, callers, databases);
			}
		}
	}

	private List<ObjectNode> databaseLinks(List<String> callers) {
		List<ObjectNode> links = new ArrayList<>();
		for (String caller : callers) {
			this.design.edges()
				.stream()
				.filter((edge) -> edge.path("source").stringValue().equals(caller))
				.filter((edge) -> this.design.node(edge.path("target").stringValue())
					.filter((target) -> isType(target, "database"))
					.isPresent())
				.forEach(links::add);
		}
		return links;
	}

	/**
	 * Whether every caller of a cache is a service that reads one database itself.
	 * @param callers the cache's callers
	 * @param databaseLinks their links to databases
	 * @return {@code true} for cache-aside
	 */
	private boolean isCacheAside(List<String> callers, List<ObjectNode> databaseLinks) {
		Set<String> databases = new HashSet<>();
		Set<String> readers = new HashSet<>();
		databaseLinks.forEach((link) -> {
			databases.add(link.path("target").stringValue());
			readers.add(link.path("source").stringValue());
		});
		if (callers.isEmpty() || databases.size() != 1 || !readers.equals(new HashSet<>(callers))) {
			return false;
		}
		return callers.stream().allMatch(this::isService)
				&& databaseLinks.stream().allMatch(ImportFinish::carriesReads);
	}

	private boolean isService(String nodeId) {
		return this.design.node(nodeId).filter((node) -> isType(node, "service")).isPresent();
	}

	private void readOnMiss(String cacheId, List<ObjectNode> databaseLinks) {
		for (ObjectNode link : databaseLinks) {
			((ObjectNode) link.path("config")).put("readsOnMiss", true);
			String caller = link.path("source").stringValue();
			this.log.guessed(caller + " reads " + link.path("target").stringValue() + " only on a " + cacheId
					+ " miss (cache-aside: " + caller + " calls both)");
		}
	}

	private void missTo(String cacheId, List<String> callers, List<String> databases) {
		Set<String> used = new LinkedHashSet<>();
		callers.forEach((caller) -> this.design.callees(caller, null)
			.stream()
			.filter((callee) -> this.design.node(callee).filter((node) -> isType(node, "database")).isPresent())
			.forEach(used::add));
		String target;
		String why;
		if (used.size() == 1) {
			target = used.iterator().next();
			List<String> users = callers.stream()
				.filter((caller) -> this.design.callees(caller, null).contains(target))
				.toList();
			why = String.join(", ", users) + " also call" + ((users.size() == 1) ? "s" : "") + " it";
		}
		else if (used.isEmpty() && databases.size() == 1) {
			target = databases.getFirst();
			why = "the only database";
		}
		else {
			return;
		}
		if (this.design.link(cacheId, new ExportedLink(target, "miss", List.of("read"), Map.of()))) {
			this.log.guessed("link " + cacheId + " -> " + target + " on its miss port (reads that miss; " + why + ")");
		}
	}

	private static boolean carriesReads(ObjectNode link) {
		for (JsonNode type : link.path("config").path("requestTypes")) {
			if ("read".equals(type.asString(null))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isType(JsonNode node, String type) {
		return node.path("type").stringValue().equals(type);
	}

}
