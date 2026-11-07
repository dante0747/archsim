package io.archsim.app.calibration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A resolver that answers names from a table, each name's answers in turn (its last
 * answer again after that), and every other name as the system does; it remembers the
 * names it was asked, in order.
 */
final class TableResolver implements HostResolver {

	private final Map<String, List<List<String>>> answers;

	private final List<String> asked = new CopyOnWriteArrayList<>();

	private TableResolver(Map<String, List<List<String>>> answers) {
		this.answers = Map.copyOf(answers);
	}

	/**
	 * A resolver that always answers a name with the same addresses.
	 * @param name the name
	 * @param addresses its addresses, as literals
	 * @return the resolver
	 */
	static TableResolver answering(String name, List<String> addresses) {
		return new TableResolver(Map.of(name, List.of(addresses)));
	}

	/**
	 * A resolver whose answer for a name changes after the first time it is asked, as a
	 * name under an attacker's control can (DNS rebinding).
	 * @param name the name
	 * @param first its addresses the first time
	 * @param later its addresses every time after
	 * @return the resolver
	 */
	static TableResolver rebinding(String name, List<String> first, List<String> later) {
		return new TableResolver(Map.of(name, List.of(first, later)));
	}

	@Override
	public List<InetAddress> resolve(String host) throws UnknownHostException {
		List<List<String>> turns = this.answers.get(host);
		if (turns == null) {
			return HostResolver.SYSTEM.resolve(host);
		}
		int turn = (int) this.asked.stream().filter(host::equals).count();
		this.asked.add(host);
		return turns.get(Math.min(turn, turns.size() - 1)).stream().map(InetAddress::ofLiteral).toList();
	}

	/**
	 * The names from the table it was asked, in order.
	 * @return the names
	 */
	List<String> asked() {
		return List.copyOf(this.asked);
	}

}
