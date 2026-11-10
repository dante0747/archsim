package io.archsim.engine.calibration.prometheus.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Self-signed certificates for one DNS name each, made by the JDK's {@code keytool} for
 * the TLS tests: a server's key and certificate, and a client that trusts it.
 */
final class TestCertificates {

	/** The test key stores' password: they live in a temporary directory. */
	private static final char[] PASSWORD = "test-only".toCharArray();

	private static final long KEYTOOL_SECONDS = 60;

	private final KeyStore keys;

	private TestCertificates(KeyStore keys) {
		this.keys = keys;
	}

	/**
	 * A key and a self-signed certificate valid for one DNS name.
	 * @param directory where the key store is written
	 * @param name the DNS name the certificate names, and nothing else
	 * @return the certificate
	 */
	static TestCertificates forName(Path directory, String name) {
		Path store = directory.resolve(name + ".p12");
		run(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-keystore",
				store.toString(), "-storetype", "PKCS12", "-storepass", new String(PASSWORD), "-alias", "server",
				"-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + name, "-ext", "SAN=dns:" + name,
				"-validity", "2"));
		try (InputStream in = Files.newInputStream(store)) {
			KeyStore keys = KeyStore.getInstance("PKCS12");
			keys.load(in, PASSWORD);
			return new TestCertificates(keys);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * TLS for a server that presents this certificate.
	 * @return the server's TLS
	 */
	SSLContext server() {
		try {
			KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			managers.init(this.keys, PASSWORD);
			SSLContext tls = SSLContext.getInstance("TLS");
			tls.init(managers.getKeyManagers(), null, null);
			return tls;
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * TLS for a client that trusts this certificate and nothing else.
	 * @return the client's TLS
	 */
	SSLContext trusting() {
		try {
			KeyStore trusted = KeyStore.getInstance("PKCS12");
			trusted.load(null, null);
			trusted.setCertificateEntry("server", this.keys.getCertificate("server"));
			TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			managers.init(trusted);
			SSLContext tls = SSLContext.getInstance("TLS");
			tls.init(null, managers.getTrustManagers(), null);
			return tls;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void run(List<String> command) {
		try {
			Process keytool = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!keytool.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
				throw new IllegalStateException("keytool failed: " + output);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
