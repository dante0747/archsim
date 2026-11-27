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
 * @param databaseUrl the PostgreSQL database, as a URL
 * ({@code postgresql://user:password@host:5432/db}, or SQLAlchemy's
 * {@code postgresql+psycopg://}) or a JDBC one
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
 * @param authMode whether people sign in ({@code oauth}) or every request is the local
 * owner of the default workspace ({@code none})
 * @param trustedProxies addresses whose {@code X-ArchSim-Client-IP} header is believed:
 * the web server's, as IPs, networks or host names
 * @param rateLimits whether the limits per workspace, person and address apply (429 with
 * {@code Retry-After}), with the hosted limits per workspace kind; empty for on with
 * sign-in and off without
 * @param runHistoryLimit how many of a project's runs its list shows
 * @param maxEngineRequests requests the engine's routes answer at once; empty for as many
 * as the heap holds, at most 16
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
		@DefaultValue("5000000") @Min(10_000) long maxBodyBytes, @Nullable String publicUrl,
		@DefaultValue("none") AuthMode authMode, @DefaultValue("127.0.0.1,::1") HostList trustedProxies,
		@Nullable Boolean rateLimits, @DefaultValue("200") @Min(1) int runHistoryLimit,
		@Min(1) @Max(MAX_ENGINE_REQUESTS) @Nullable Integer maxEngineRequests) {

	/** The most runs one worker simulates at once. */
	static final int MAX_SLOTS = 32;

	/** The most requests the engine's routes may be set to answer at once. */
	static final int MAX_ENGINE_REQUESTS = 64;

	/**
	 * The most requests the engine's routes answer at once by the heap, without the
	 * setting.
	 */
	static final int MAX_HEAP_SIZED_ENGINE_REQUESTS = 16;

	/** The heap the rest of the API keeps, beside the engine's requests: 256 MiB. */
	private static final long API_HEAP = 256L << 20;

	/**
	 * The heap an engine request, or a calibration's replay, is given for each byte of
	 * the largest body: a body read and loaded holds about 16 times its size (a 5 MB
	 * design about 77 MB), and its analysis or replay more (ADR-0108).
	 */
	private static final long HEAP_PER_BODY_BYTE = 25;

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
	 * Requests the engine's routes answer at once, each with a design in memory
	 * (ADR-0108): the setting, else as many as this process's heap holds.
	 * @return the requests answered at once
	 */
	public int engineRequests() {
		if (this.maxEngineRequests != null) {
			return this.maxEngineRequests;
		}
		return engineRequestsFor(Runtime.getRuntime().maxMemory(), replaySlots(), this.maxBodyBytes);
	}

	/**
	 * Requests the engine's routes answer at once on a heap. Each is given 25 bytes of
	 * heap per byte of the largest body (125 MB at 5 MB); the rest of the API keeps 256
	 * MiB, and each replay slot as much as a request. The count is rounded to the
	 * nearest, since a JVM reports a little less than its {@code -Xmx}, and kept between
	 * 1 and {@value #MAX_HEAP_SIZED_ENGINE_REQUESTS}.
	 * @param maxHeapBytes the most heap the process may use, as the JVM reports it
	 * @param replays the replay slots
	 * @param maxBodyBytes the largest request body
	 * @return the requests answered at once
	 */
	static int engineRequestsFor(long maxHeapBytes, int replays, long maxBodyBytes) {
		double perRequest = (double) maxBodyBytes * HEAP_PER_BODY_BYTE;
		double left = maxHeapBytes - API_HEAP - replays * perRequest;
		return Math.clamp(Math.round(left / perRequest), 1, MAX_HEAP_SIZED_ENGINE_REQUESTS);
	}

	/**
	 * Calibrations the API replays at once, each on a platform thread of its own
	 * (ADR-0054): one per CPU this process may use less one, left to its other requests.
	 * @return the replay threads
	 */
	public int replayThreads() {
		return replayThreadsFor(Runtime.getRuntime().availableProcessors());
	}

	/**
	 * Calibrations the API answers at once (ADR-0108): one per replay thread, but no more
	 * than this process's heap holds beside the rest of the API and one engine request.
	 * @return the replay slots
	 */
	public int replaySlots() {
		return replaySlotsFor(Runtime.getRuntime().maxMemory(), replayThreads(), this.maxBodyBytes);
	}

	/**
	 * Calibrations answered at once on a heap: as many replays as fit, each given what an
	 * engine request is, beside the 256 MiB the rest of the API keeps and one engine
	 * request, rounded to the nearest, between 1 and the replay threads.
	 * @param maxHeapBytes the most heap the process may use, as the JVM reports it
	 * @param threads the replay threads
	 * @param maxBodyBytes the largest request body
	 * @return the replay slots
	 */
	static int replaySlotsFor(long maxHeapBytes, int threads, long maxBodyBytes) {
		double perRequest = (double) maxBodyBytes * HEAP_PER_BODY_BYTE;
		double left = maxHeapBytes - API_HEAP - perRequest;
		return Math.clamp(Math.round(left / perRequest), 1, threads);
	}

	/**
	 * Calibrations replayed at once on a machine.
	 * @param processors the processors the JVM sees
	 * @return the processors less one, at least one
	 */
	static int replayThreadsFor(int processors) {
		return Math.max(1, processors - 1);
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
	 * The address people open, as links to the web app start: without the slashes the
	 * setting ends with.
	 * @return the address, or empty when the setting names none
	 */
	public Optional<String> publicAddress() {
		return Optional.ofNullable(this.publicUrl)
			.filter((url) -> !url.isEmpty())
			.map(Addresses::withoutTrailingSlashes);
	}

	/**
	 * Whether people reach the API over HTTPS, so browsers should keep to it.
	 * @return {@code true} when the public address is an {@code https://} one
	 */
	public boolean servedOverHttps() {
		return this.publicUrl != null && this.publicUrl.startsWith("https://");
	}

	/**
	 * Whether people other than whoever runs the server reach it: they sign in.
	 * @return {@code true} with {@code ARCHSIM_AUTH_MODE=oauth}
	 */
	public boolean hosted() {
		return this.authMode == AuthMode.OAUTH;
	}

	/**
	 * Whether the rate limits and the hosted limits per workspace kind apply.
	 * @return the setting, else whether people sign in
	 */
	public boolean limitsOn() {
		return (this.rateLimits != null) ? this.rateLimits : hosted();
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
				+ "maxConcurrentRuns=%s, maxWallSeconds=%s, maxRequests=%s, maxBodyBytes=%s, publicUrl=%s, "
				+ "authMode=%s, trustedProxies=%s, rateLimits=%s, runHistoryLimit=%s, maxEngineRequests=%s]")
			.formatted(DatabaseUrl.masked(this.databaseUrl), this.examplesDir, this.pricesDir,
					this.corsOrigins.origins(), this.autoMigrate, this.maxConcurrentRuns, this.maxWallSeconds,
					this.maxRequests, this.maxBodyBytes, this.publicUrl, this.authMode, this.trustedProxies.hosts(),
					this.rateLimits, this.runHistoryLimit, this.maxEngineRequests);
	}

}
