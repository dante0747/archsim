package io.archsim.app.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Secrets handed out once and kept only as their SHA-256: session cookies, API tokens and
 * invitation links. A stolen table holds nothing that signs anyone in.
 */
public final class Secrets {

	/** Personal API tokens start with this, so secret scanners and people know them. */
	public static final String TOKEN_PREFIX = "asim_pat_";

	/** The random bytes in a secret: 256 bits. */
	private static final int SECRET_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private Secrets() {
	}

	/**
	 * A new secret: 256 random bits, URL-safe, after a prefix.
	 * @param prefix what the secret starts with, possibly nothing
	 * @return the prefix and 43 characters of base64url
	 */
	public static String newSecret(String prefix) {
		byte[] bytes = new byte[SECRET_BYTES];
		RANDOM.nextBytes(bytes);
		return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * What is stored of a secret.
	 * @param value the secret
	 * @return its SHA-256 of its UTF-8 bytes, in lower-case hex
	 */
	public static String sha256Hex(String value) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("every Java runtime has SHA-256", ex);
		}
	}

}
