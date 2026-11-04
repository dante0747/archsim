package io.archsim.engine.estimate;

import io.archsim.engine.load.NodeLoad;
import io.archsim.model.codec.JsonEnum;

/** The kind of request: reads and writes take their own paths and times. */
public enum Op implements JsonEnum {

	/** A read. */
	READ("read"),

	/** A write. */
	WRITE("write");

	private final String json;

	Op(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * The requests of this kind in a load.
	 * @param load a part's or link's load
	 * @return its reads or its writes per second
	 */
	double rate(NodeLoad load) {
		return (this == READ) ? load.read() : load.write();
	}

}
