package io.archsim.benchmarks.provenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;

/**
 * The engine's build that runs in this process: a SHA-256 over the jars that hold the
 * engine and its model, each by its name and its own SHA-256, as the Python harness
 * hashed the installed command line's jars. It may be older than the sources a provenance
 * names.
 */
public final class EngineBuild {

	private static final String ALGORITHM = "SHA-256";

	private static final List<Class<?>> ENGINE_CLASSES = List.of(Simulator.class, StandardComponents.class);

	private EngineBuild() {
	}

	/**
	 * The build of the engine this process runs.
	 * @return its hash; empty when the engine runs from folders of classes, not jars
	 */
	public static Optional<String> current() {
		List<Optional<Path>> jars = ENGINE_CLASSES.stream().map(EngineBuild::jar).toList();
		if (jars.stream().anyMatch(Optional::isEmpty)) {
			return Optional.empty();
		}
		return Optional.of(digest(jars.stream().map(Optional::orElseThrow).distinct().toList()));
	}

	/**
	 * The hash of some jars: over each one's name, a zero byte and its SHA-256, in the
	 * order of their names.
	 * @param jars the jars
	 * @return the hash, in hexadecimal
	 * @throws UncheckedIOException when a jar cannot be read
	 */
	static String digest(List<Path> jars) {
		MessageDigest build = sha256();
		for (Path jar : jars.stream().sorted(Comparator.comparing(EngineBuild::name)).toList()) {
			build.update(name(jar).getBytes(StandardCharsets.UTF_8));
			build.update((byte) 0);
			try {
				build.update(sha256().digest(Files.readAllBytes(jar)));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return HexFormat.of().formatHex(build.digest());
	}

	// the jar a class was loaded from, if it was
	private static Optional<Path> jar(Class<?> type) {
		CodeSource source = type.getProtectionDomain().getCodeSource();
		if (source == null) {
			return Optional.empty();
		}
		try {
			Path location = Path.of(source.getLocation().toURI());
			return name(location).endsWith(".jar") ? Optional.of(location) : Optional.empty();
		}
		catch (URISyntaxException ex) {
			return Optional.empty();
		}
	}

	private static String name(Path path) {
		Path name = path.getFileName();
		return (name != null) ? name.toString() : "";
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance(ALGORITHM);
		}
		catch (NoSuchAlgorithmException ex) {
			// every Java platform has SHA-256 (MessageDigest's contract)
			throw new IllegalStateException(ex);
		}
	}

}
