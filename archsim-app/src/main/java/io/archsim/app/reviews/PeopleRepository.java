package io.archsim.app.reviews;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * People, as comments, sign-offs and the history name them.
 */
@Repository
class PeopleRepository {

	private final JdbcClient jdbc;

	PeopleRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The people some comments, sign-offs or changes name.
	 * @param ids their user ids; {@code null} where one names nobody
	 * @return each one found
	 */
	People named(Collection<? extends @Nullable String> ids) {
		// mapMulti, not filter(Objects::nonNull), so that NullAway sees the ids left as
		// non-null
		List<String> wanted = ids.stream().<String>mapMulti((id, found) -> {
			if (id != null) {
				found.accept(id);
			}
		}).distinct().toList();
		if (wanted.isEmpty()) {
			return new People(Map.of());
		}
		return new People(this.jdbc.sql("SELECT id, name, avatar_url FROM users WHERE id IN (:ids)")
			.param("ids", wanted)
			.query((rows, number) -> new PersonView(rows.getString("id"), rows.getString("name"),
					rows.getString("avatar_url")))
			.list()
			.stream()
			.collect(Collectors.toMap(PersonView::id, Function.identity())));
	}

}
