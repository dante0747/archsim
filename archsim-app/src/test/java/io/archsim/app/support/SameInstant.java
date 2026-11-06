package io.archsim.app.support;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.archsim.app.store.Ids;

/**
 * Stores rows at the same instant, as two of the API's servers can, to check that a query
 * orders them by id after the time.
 */
public final class SameInstant {

	/** The instant the rows are stored at: nine o'clock. */
	public static final Instant NINE = Instant.parse("2026-11-06T09:00:00Z");

	private SameInstant() {
	}

	/**
	 * Stores three rows with new ids: the middle id first, then the highest, then the
	 * lowest. PostgreSQL answers rows that tie in the order it finds them: as they were
	 * stored, or the reverse when it reads an index backwards for a descending order.
	 * Neither is the ids' order or its reverse, neither starts with the highest id, and
	 * as stored they don't start with the lowest: so a query that does not order by id
	 * after the time gets its list, or its pick of one row, wrong.
	 * @param store stores one row at {@link #NINE}, with the id it is given
	 * @return the rows' ids, lowest first
	 */
	public static List<String> storeThree(Consumer<String> store) {
		List<String> lowestFirst = Stream.of(Ids.newId(), Ids.newId(), Ids.newId()).sorted().toList();
		String lowest = lowestFirst.getFirst();
		String middle = lowestFirst.get(1);
		String highest = lowestFirst.getLast();
		Stream.of(middle, highest, lowest).forEach(store);
		return lowestFirst;
	}

}
