package io.archsim.tools.release;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a checkout says about its release: ArchSim's version, as Gradle reads it from
 * {@code gradle.properties} (ADR-0076), and the images on the {@code image:} lines of
 * each compose file that runs a release: the local stack's {@code docker-compose.yml}
 * (ADR-0077) and the hosted kit's {@code deploy/compose.yaml} (ADR-0080). The web app's
 * {@code package.json}, which repeats the version, is held to it by the web app's own
 * tests.
 *
 * @param version the version {@code gradle.properties} names
 * @param composeFiles the compose files, in the order of {@link #COMPOSE_FILES}
 */
record Checkout(String version, List<ComposeFile> composeFiles) {

	/** The compose files that run a release's images, from the checkout's root. */
	static final List<String> COMPOSE_FILES = List.of("docker-compose.yml", "deploy/compose.yaml");

	/** ArchSim's images, which a release publishes and compose runs. */
	private static final List<String> ARCHSIM_IMAGES = List.of("archsim-app", "archsim-web");

	/** An {@code image:} line of compose's, not a comment. */
	private static final Pattern IMAGE = Pattern.compile("(?m)^\\s*image:\\s*(\\S+)\\s*$");

	Checkout {
		composeFiles = List.copyOf(composeFiles);
	}

	/**
	 * What a checkout says.
	 * @param repository the checkout's root
	 * @return its version and its compose files' images
	 * @throws IOException when {@code gradle.properties} or a compose file cannot be read
	 */
	static Checkout read(Path repository) throws IOException {
		List<ComposeFile> composeFiles = new ArrayList<>();
		for (String path : COMPOSE_FILES) {
			composeFiles.add(ComposeFile.read(repository, path));
		}
		return new Checkout(gradleVersion(repository), composeFiles);
	}

	/**
	 * The version {@code gradle.properties} names, read as Gradle reads it.
	 * @param repository the checkout's root
	 * @return the version; empty when none is set
	 * @throws IOException when the file cannot be read
	 */
	static String gradleVersion(Path repository) throws IOException {
		Properties gradle = new Properties();
		try (Reader properties = Files.newBufferedReader(repository.resolve("gradle.properties"),
				StandardCharsets.ISO_8859_1)) {
			gradle.load(properties);
		}
		return gradle.getProperty("version", "");
	}

	/**
	 * What stops releasing this checkout under a tag to a registry: a tag that names
	 * another version, or an ArchSim image a compose file does not run at the version.
	 * @param tag the release's tag, {@code v} and the version
	 * @param registry where the workflow pushes the images
	 * @return each problem, in words; none when the release can go ahead
	 */
	List<String> problems(String tag, String registry) {
		List<String> problems = new ArrayList<>();
		if (!("v" + this.version).equals(tag)) {
			problems.add("the tag %s does not name gradle.properties' version, %s".formatted(tag, this.version));
		}
		this.composeFiles.forEach((composeFile) -> problems.addAll(missingImages(composeFile, registry)));
		return problems;
	}

	// ArchSim's images, from the registry at the version, that a compose file does not
	// run
	private List<String> missingImages(ComposeFile composeFile, String registry) {
		return ARCHSIM_IMAGES.stream()
			.map((name) -> registry + "/" + name + ":" + this.version)
			.filter((image) -> !composeFile.images().contains(image))
			.map((image) -> composeFile.path() + " does not run " + image)
			.toList();
	}

	/**
	 * Whether the release moves {@code latest}: a release that is not a pre-release
	 * ({@code 1.1.0}, not {@code 1.1.0-rc.1}).
	 * @return whether it does
	 */
	boolean movesLatest() {
		return !this.version.contains("-");
	}

	/**
	 * A compose file and the images it runs.
	 *
	 * @param path the file, from the checkout's root
	 * @param images the images on its {@code image:} lines, comments left out
	 */
	record ComposeFile(String path, Set<String> images) {

		ComposeFile {
			images = Set.copyOf(images);
		}

		/**
		 * A checkout's compose file.
		 * @param repository the checkout's root
		 * @param path the file, from the checkout's root
		 * @return the file and its images
		 * @throws IOException when the file cannot be read
		 */
		static ComposeFile read(Path repository, String path) throws IOException {
			Matcher image = IMAGE.matcher(Files.readString(repository.resolve(path)));
			return new ComposeFile(path, image.results().map((found) -> found.group(1)).collect(Collectors.toSet()));
		}

	}

}
