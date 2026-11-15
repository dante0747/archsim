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
 * {@code gradle.properties} (ADR-0076), and the images {@code docker-compose.yml} runs.
 * The web app's {@code package.json}, which repeats the version, is held to it by the web
 * app's own tests.
 *
 * @param version the version {@code gradle.properties} names
 * @param images the images on compose's {@code image:} lines, comments left out
 */
record Checkout(String version, Set<String> images) {

	/** ArchSim's images, which a release publishes and compose runs. */
	private static final List<String> ARCHSIM_IMAGES = List.of("archsim-app", "archsim-web");

	/** An {@code image:} line of compose's, not a comment. */
	private static final Pattern IMAGE = Pattern.compile("(?m)^\\s*image:\\s*(\\S+)\\s*$");

	Checkout {
		images = Set.copyOf(images);
	}

	/**
	 * What a checkout says.
	 * @param repository the checkout's root
	 * @return its version and compose's images
	 * @throws IOException when {@code gradle.properties} or {@code docker-compose.yml}
	 * cannot be read
	 */
	static Checkout read(Path repository) throws IOException {
		return new Checkout(gradleVersion(repository), composeImages(repository));
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

	private static Set<String> composeImages(Path repository) throws IOException {
		Matcher image = IMAGE.matcher(Files.readString(repository.resolve("docker-compose.yml")));
		return image.results().map((found) -> found.group(1)).collect(Collectors.toSet());
	}

	/**
	 * What stops releasing this checkout under a tag to a registry: a tag that names
	 * another version, or an ArchSim image compose does not run at the version.
	 * @param tag the release's tag, {@code v} and the version
	 * @param registry where the workflow pushes the images
	 * @return each problem, in words; none when the release can go ahead
	 */
	List<String> problems(String tag, String registry) {
		List<String> problems = new ArrayList<>();
		if (!("v" + this.version).equals(tag)) {
			problems.add("the tag %s does not name gradle.properties' version, %s".formatted(tag, this.version));
		}
		for (String name : ARCHSIM_IMAGES) {
			String image = registry + "/" + name + ":" + this.version;
			if (!this.images.contains(image)) {
				problems.add("docker-compose.yml does not run " + image);
			}
		}
		return problems;
	}

	/**
	 * Whether the release moves {@code latest}: a release that is not a pre-release
	 * ({@code 1.1.0}, not {@code 1.1.0-rc.1}).
	 * @return whether it does
	 */
	boolean movesLatest() {
		return !this.version.contains("-");
	}

}
