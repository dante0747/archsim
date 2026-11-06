package io.archsim.engine.imports;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;

/**
 * The managed and third-party technologies ArchSim's exports name, which an import gives
 * back as the parts they were.
 */
final class ManagedTechnologies {

	/**
	 * The part type of each managed technology, in the order a note or name is matched
	 * against them, which picks one for a name naming two.
	 */
	private static final SequencedMap<String, String> MANAGED = managed();

	/** Third parties' technologies, which an external API keeps. */
	private static final Set<String> THIRD_PARTY = Set.of("payments", "email", "sms", "identity", "llm",
			"object_storage");

	private ManagedTechnologies() {
	}

	/**
	 * The part type of a managed technology.
	 * @param technology the technology
	 * @return its part type; empty for one that is not managed
	 */
	static Optional<String> typeOf(String technology) {
		return Optional.ofNullable(MANAGED.get(technology));
	}

	/**
	 * The managed technologies, in the order a note or name is matched against them.
	 * @return them, in order
	 */
	static SequencedSet<String> inOrder() {
		return MANAGED.sequencedKeySet();
	}

	static boolean isThirdParty(String technology) {
		return THIRD_PARTY.contains(technology);
	}

	private static SequencedMap<String, String> managed() {
		SequencedMap<String, String> managed = new LinkedHashMap<>();
		managed.put("aws_alb", "load_balancer");
		managed.put("api_gateway", "load_balancer");
		managed.put("cdn", "cache");
		managed.put("pubsub", "message_queue");
		managed.put("sqs", "message_queue");
		return Collections.unmodifiableSequencedMap(managed);
	}

}
