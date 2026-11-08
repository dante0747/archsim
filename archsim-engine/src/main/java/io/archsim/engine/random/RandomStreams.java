package io.archsim.engine.random;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * A run's independent random streams, one per name, derived from the run's seed: each is
 * seeded with the first 8 bytes of the SHA-256 of {@code "<seed>:<name>"}, read as a
 * big-endian unsigned integer, so a name's stream does not depend on which other streams
 * the run draws from, nor on the order they are created in.
 */
public final class RandomStreams {

	private static final int SEED_BYTES = 8;

	private final long seed;

	/**
	 * The streams of a run.
	 * @param seed the run's seed
	 */
	public RandomStreams(long seed) {
		this.seed = seed;
	}

	/**
	 * The stream of a name.
	 * @param name the stream's name, such as {@code arrivals} or {@code node:db:service}
	 * @return a new generator, at the start of the stream
	 */
	public PythonRandom stream(String name) {
		byte[] digest = sha256((this.seed + ":" + name).getBytes(StandardCharsets.UTF_8));
		return PythonRandom.seeded(new BigInteger(1, Arrays.copyOf(digest, SEED_BYTES)));
	}

	private static byte[] sha256(byte[] text) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(text);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("every Java platform has SHA-256", ex);
		}
	}

}
