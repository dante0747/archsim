package io.archsim.engine.findings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;

/**
 * The links that look a cache up for reads, and which of their callers fall back to the
 * data store when a lookup fails: a service whose link carries on after the failure and
 * that has a link set to read on a cache miss (ADR-0082). The cache's finding and the
 * advice after its outage both read it, so that they agree on who falls back.
 */
final class CacheCallers {

	private static final String MISS = "miss";

	private final ArchitectureDocument resolved;

	private final List<EdgeSpec> lookups;

	private CacheCallers(ArchitectureDocument resolved, List<EdgeSpec> lookups) {
		this.resolved = resolved;
		this.lookups = lookups;
	}

	/**
	 * A cache's callers in a design.
	 * @param resolved the design simulated
	 * @param cacheId the cache
	 * @return its callers
	 */
	static CacheCallers of(ArchitectureDocument resolved, String cacheId) {
		List<EdgeSpec> lookups = resolved.architecture()
			.edges()
			.stream()
			.filter((edge) -> edge.target().equals(cacheId) && !MISS.equals(edge.sourcePort()))
			.filter((edge) -> edge.config().carries(RequestType.READ))
			.toList();
		return new CacheCallers(resolved, lookups);
	}

	/**
	 * The links that carry reads to the cache, from any part but another cache's miss
	 * link, in the design's order.
	 * @return the links
	 */
	List<EdgeSpec> lookups() {
		return this.lookups;
	}

	/**
	 * The lookups services make: the ones whose settings can make their caller fall back.
	 * @return the links, in the design's order
	 */
	List<EdgeSpec> fromServices() {
		return this.lookups.stream().filter((edge) -> isService(edge.source())).toList();
	}

	/**
	 * Whether a lookup's caller reads the data store when the lookup fails.
	 * @param lookup one of the cache's lookups
	 * @return {@code true} when a service makes it, carries on when it fails, and has a
	 * link set to read on a cache miss
	 */
	boolean fallsBack(EdgeSpec lookup) {
		return fetch(lookup).isPresent();
	}

	/**
	 * The link a lookup's caller reads the data store with when the lookup fails.
	 * @param lookup one of the cache's lookups
	 * @return the caller's first link set to read on a cache miss, when it falls back
	 */
	Optional<EdgeSpec> fetch(EdgeSpec lookup) {
		if (!lookup.config().continueOnFailure() || !isService(lookup.source())) {
			return Optional.empty();
		}
		return fetches(lookup.source()).stream().findFirst();
	}

	/**
	 * The parts a caller reads on a cache miss.
	 * @param caller the calling part
	 * @return the targets of its links set to read on a cache miss, in the design's order
	 */
	List<String> stores(String caller) {
		return fetches(caller).stream().map(EdgeSpec::target).toList();
	}

	private List<EdgeSpec> fetches(String caller) {
		return this.resolved.architecture()
			.edges()
			.stream()
			.filter((edge) -> edge.source().equals(caller) && edge.config().readsOnMiss())
			.toList();
	}

	private boolean isService(String id) {
		return this.resolved.architecture()
			.nodes()
			.stream()
			.filter((node) -> node.id().equals(id))
			.map(NodeSpec::type)
			.anyMatch((type) -> Objects.equals(type, ServiceSpec.TYPE));
	}

}
