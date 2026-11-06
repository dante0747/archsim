package io.archsim.engine.imports;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The names a file gives its parts by (a compose service, a workload, a Kubernetes
 * Service) with each one's part id, and the links parts state ({@code archsim.calls},
 * {@code archsim.dev/calls}) as an export writes them: what an import reads its links
 * from once every part is read.
 */
final class PartNames {

	private final Map<String, String> partIds = new LinkedHashMap<>();

	private final Map<String, String> calls = new LinkedHashMap<>();

	/**
	 * Names a part, in place of any part the name named before.
	 * @param name the name
	 * @param partId the part's id
	 */
	void name(String name, String partId) {
		this.partIds.put(name, partId);
	}

	/**
	 * Names a part, unless the name already names one.
	 * @param name the name
	 * @param partId the part's id
	 */
	void nameIfUnnamed(String name, String partId) {
		this.partIds.putIfAbsent(name, partId);
	}

	/**
	 * Records the links a part states.
	 * @param partId the part's id
	 * @param text the links, as an export writes them
	 */
	void stateCalls(String partId, String text) {
		this.calls.put(partId, text);
	}

	/**
	 * The part a name names.
	 * @param name the name
	 * @return the part's id; empty when the name names none
	 */
	Optional<String> partId(String name) {
		return Optional.ofNullable(this.partIds.get(name));
	}

	/**
	 * Whether a name names a part.
	 * @param name the name
	 * @return {@code true} when it does
	 */
	boolean names(String name) {
		return this.partIds.containsKey(name);
	}

	/**
	 * The parts named, a part named twice once for each name.
	 * @return their ids, in the order they were first named
	 */
	Collection<String> partIds() {
		return Collections.unmodifiableCollection(this.partIds.values());
	}

	/**
	 * The links a part states.
	 * @param partId the part's id
	 * @return the links, as an export writes them; empty when it states none
	 */
	Optional<String> statedCalls(String partId) {
		return Optional.ofNullable(this.calls.get(partId));
	}

	/**
	 * The links every part states.
	 * @return the links, as an export writes them, by part id, in the order stated
	 */
	Map<String, String> statedCalls() {
		return Collections.unmodifiableMap(this.calls);
	}

}
