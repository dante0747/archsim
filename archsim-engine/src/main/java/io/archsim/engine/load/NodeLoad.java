package io.archsim.engine.load;

/**
 * The requests a part or link carries per second.
 *
 * @param read reads per second
 * @param write writes per second
 */
public record NodeLoad(double read, double write) {

	/** No requests. */
	public static final NodeLoad NONE = new NodeLoad(0.0, 0.0);

	/**
	 * All the requests.
	 * @return reads and writes per second
	 */
	public double total() {
		return this.read + this.write;
	}

	/**
	 * This load and another together.
	 * @param other the other load
	 * @return their sum, by request type
	 */
	public NodeLoad plus(NodeLoad other) {
		return new NodeLoad(this.read + other.read, this.write + other.write);
	}

}
