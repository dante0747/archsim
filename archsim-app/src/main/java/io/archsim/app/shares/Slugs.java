package io.archsim.app.shares;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Share links' ids: 128 random bits written in base62, 22 characters, as the Python API
 * writes them (the lowest digit first).
 */
final class Slugs {

	/** How many characters a slug has: 62^22 is more than 2^128. */
	static final int LENGTH = 22;

	private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	private static final Pattern SLUG = Pattern.compile("[0-9A-Za-z]{" + LENGTH + "}");

	private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length());

	private static final int BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private Slugs() {
	}

	/**
	 * A new slug.
	 * @return 22 characters of base62
	 */
	static String next() {
		return of(new BigInteger(BITS, RANDOM));
	}

	/**
	 * A number's slug.
	 * @param number from 0 to 2^128 - 1
	 * @return its 22 base62 digits, the lowest first
	 */
	static String of(BigInteger number) {
		StringBuilder slug = new StringBuilder(LENGTH);
		BigInteger rest = number;
		for (int i = 0; i < LENGTH; i++) {
			BigInteger[] divided = rest.divideAndRemainder(BASE);
			slug.append(ALPHABET.charAt(divided[1].intValue()));
			rest = divided[0];
		}
		return slug.toString();
	}

	/**
	 * Whether a text could be a slug at all, so a lookup is worth making.
	 * @param text the text
	 * @return {@code true} for 22 base62 characters
	 */
	static boolean looksLikeOne(String text) {
		return SLUG.matcher(text).matches();
	}

}
