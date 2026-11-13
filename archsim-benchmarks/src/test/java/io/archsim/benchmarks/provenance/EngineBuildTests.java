package io.archsim.benchmarks.provenance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's build that ran: a hash over its jars, by name, so the same build gives the
 * same hash wherever it is installed.
 */
class EngineBuildTests {

	@Test
	void digest_twoJars_hashesEachJarsNameAndHashInTheOrderOfTheirNames(@TempDir Path folder)
			throws IOException, NoSuchAlgorithmException {
		Path second = Files.write(folder.resolve("b.jar"), "second".getBytes(StandardCharsets.UTF_8));
		Path first = Files.write(folder.resolve("a.jar"), "first".getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.writeBytes("a.jar\0".getBytes(StandardCharsets.UTF_8));
		expected.writeBytes(sha256("first".getBytes(StandardCharsets.UTF_8)));
		expected.writeBytes("b.jar\0".getBytes(StandardCharsets.UTF_8));
		expected.writeBytes(sha256("second".getBytes(StandardCharsets.UTF_8)));

		String digest = EngineBuild.digest(List.of(second, first));

		assertThat(digest).isEqualTo(HexFormat.of().formatHex(sha256(expected.toByteArray())));
	}

	private static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-256").digest(bytes);
	}

}
