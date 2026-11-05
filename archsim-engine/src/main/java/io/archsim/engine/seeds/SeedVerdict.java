package io.archsim.engine.seeds;

import io.archsim.model.codec.JsonEnum;

/** What a group of runs that differ only in their seed says about the design. */
public enum SeedVerdict implements JsonEnum {

	/** Every finished run meets the objectives. */
	HOLDS("holds"),

	/** Every finished run misses them. */
	FAILS("fails"),

	/** Some runs meet them and some miss them: the verdict is luck as much as design. */
	BORDERLINE("borderline");

	private final String json;

	SeedVerdict(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
