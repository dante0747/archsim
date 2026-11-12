package io.archsim.app.access;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import io.archsim.app.config.SignInProperties;

/**
 * Signs with keys derived from {@code ARCHSIM_SECRET_KEY}, one per purpose (the sign-in
 * state cookie), as the Python API derives them, so a value one API signs the other
 * accepts. Only a server with sign-in has a secret key.
 */
@Component
public class SigningKeys {

	private static final String HMAC_SHA256 = "HmacSHA256";

	private final SignInProperties properties;

	/**
	 * Keys derived from the settings' secret key.
	 * @param properties the sign-in settings
	 */
	public SigningKeys(SignInProperties properties) {
		this.properties = properties;
	}

	/**
	 * The HMAC-SHA256 of some bytes, under the key of a purpose.
	 * @param purpose what the signature is for, such as {@code csrf}
	 * @param data the bytes to sign
	 * @return the signature
	 * @throws IllegalStateException when no secret key is set
	 */
	public byte[] mac(String purpose, byte[] data) {
		return hmac(key(purpose), data);
	}

	private byte[] key(String purpose) {
		String secret = this.properties.secretKey();
		if (secret == null) {
			throw new IllegalStateException("signing needs ARCHSIM_SECRET_KEY");
		}
		return hmac(secret.getBytes(StandardCharsets.UTF_8), ("archsim:" + purpose).getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] hmac(byte[] key, byte[] data) {
		try {
			Mac mac = Mac.getInstance(HMAC_SHA256);
			mac.init(new SecretKeySpec(key, HMAC_SHA256));
			return mac.doFinal(data);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("every Java runtime has HMAC-SHA256", ex);
		}
	}

}
