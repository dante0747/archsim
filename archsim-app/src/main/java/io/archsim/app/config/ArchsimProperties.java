package io.archsim.app.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * ArchSim's settings, read from the same {@code ARCHSIM_*} environment variables as the
 * Python API ({@code ARCHSIM_MAX_BODY_BYTES} is {@code maxBodyBytes}), so one compose
 * file serves either.
 *
 * @param databaseUrl the PostgreSQL database, as a SQLAlchemy URL
 * ({@code postgresql+psycopg://user:password@host:5432/db}) or a JDBC one
 * @param examplesDir where the example designs are; empty to look for {@code examples/}
 * in the working directory and its parents
 * @param pricesDir more folders of price snapshots, separated by the platform's path
 * separator, besides the shipped ones
 * @param corsOrigins the web origins that may call the API from a browser
 * @param autoMigrate whether the {@code api} role migrates the database when it starts
 * @param maxConcurrentRuns runs a worker simulates at once; empty for the CPU count less
 * one
 * @param maxWallSeconds the most wall-clock time a run may take
 * @param maxRequests the most requests a run may simulate
 * @param maxBodyBytes the largest request body the API accepts
 * @param publicUrl the address people open, such as {@code https://archsim.example.com}
 */
@ConfigurationProperties("archsim")
@Validated
public record ArchsimProperties(
		@DefaultValue("postgresql://archsim:archsim@127.0.0.1:5432/archsim") @NotBlank String databaseUrl,
		@Nullable String examplesDir, @Nullable String pricesDir,
		@DefaultValue("http://localhost:3000") CorsOrigins corsOrigins, @DefaultValue("true") boolean autoMigrate,
		@Min(1) @Max(MAX_SLOTS) @Nullable Integer maxConcurrentRuns,
		@DefaultValue("300.0") @Positive @DecimalMax("3600") double maxWallSeconds,
		@DefaultValue("2000000") @Min(1) @Max(20_000_000) long maxRequests,
		@DefaultValue("5000000") @Min(10_000) long maxBodyBytes, @Nullable String publicUrl) {

	/** The most runs one worker simulates at once. */
	static final int MAX_SLOTS = 32;

	private static final String EXAMPLES = "examples";

	/**
	 * Runs a worker simulates at once: the setting, else one per CPU this process may use
	 * less one left for the API and the database, between 1 and {@value #MAX_SLOTS}.
	 * @return the runs simulated at once
	 */
	public int concurrentRuns() {
		if (this.maxConcurrentRuns != null) {
			return this.maxConcurrentRuns;
		}
		return Math.clamp(Runtime.getRuntime().availableProcessors() - 1, 1, MAX_SLOTS);
	}

	/**
	 * The folder of the example designs: the setting, else the first {@code examples/}
	 * with designs in it, in the working directory or one of its parents.
	 * @return the folder, which may not exist
	 */
	public Path examples() {
		if (this.examplesDir != null && !this.examplesDir.isBlank()) {
			return Path.of(this.examplesDir);
		}
		Path start = Path.of("").toAbsolutePath();
		for (Path dir = start; dir != null; dir = dir.getParent()) {
			Path candidate = dir.resolve(EXAMPLES);
			if (hasDesigns(candidate)) {
				return candidate;
			}
		}
		return start.resolve(EXAMPLES);
	}

	/**
	 * The folders of price snapshots besides the shipped ones.
	 * @return the folders, in the order they are given
	 */
	public List<Path> priceFolders() {
		return Optional.ofNullable(this.pricesDir)
			.map((folders) -> Arrays.stream(folders.split(File.pathSeparator))
				.filter((folder) -> !folder.isBlank())
				.map(Path::of)
				.toList())
			.orElse(List.of());
	}

	/**
	 * Whether people reach the API over HTTPS, so browsers should keep to it.
	 * @return {@code true} when the public address is an {@code https://} one
	 */
	public boolean servedOverHttps() {
		return this.publicUrl != null && this.publicUrl.startsWith("https://");
	}

	private static boolean hasDesigns(Path folder) {
		if (!Files.isDirectory(folder)) {
			return false;
		}
		try (DirectoryStream<Path> designs = Files.newDirectoryStream(folder, "*.json")) {
			return designs.iterator().hasNext();
		}
		catch (IOException ex) {
			return false; // an unreadable folder holds no designs we can serve
		}
	}

	/**
	 * The settings, with the database's password masked: the URL may hold one.
	 * @return the settings, fit for a log
	 */
	@Override
	public String toString() {
		return ("ArchsimProperties[databaseUrl=%s, examplesDir=%s, pricesDir=%s, corsOrigins=%s, autoMigrate=%s, "
				+ "maxConcurrentRuns=%s, maxWallSeconds=%s, maxRequests=%s, maxBodyBytes=%s, publicUrl=%s]")
			.formatted(DatabaseUrl.masked(this.databaseUrl), this.examplesDir, this.pricesDir,
					this.corsOrigins.origins(), this.autoMigrate, this.maxConcurrentRuns, this.maxWallSeconds,
					this.maxRequests, this.maxBodyBytes, this.publicUrl);
	}

}
