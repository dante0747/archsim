package io.archsim.benchmarks.machine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * Windows' own view of the machine while a check runs ({@link HostSamples}): PowerShell's
 * {@code Get-Counter}, one sample a second, read on a thread of its own. Off Windows, or
 * where the counters are missing, there are no samples.
 *
 * <p>
 * PowerShell gets its script encoded ({@code -EncodedCommand}), so that Windows' command
 * line leaves its quotes and backslashes alone.
 */
final class HostCounters implements AutoCloseable {

	private final HostSamples samples;

	private final @Nullable Process process;

	private HostCounters(HostSamples samples, @Nullable Process process) {
		this.samples = samples;
		this.process = process;
	}

	/**
	 * Starts reading this machine's counters, on Windows.
	 * @return the counters
	 */
	static HostCounters onThisMachine() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
			return of(new HostSamples());
		}
		HostSamples samples = new HostSamples();
		try {
			Process process = new ProcessBuilder("powershell", "-NoProfile", "-EncodedCommand", encoded(script()))
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
			Thread.ofPlatform().daemon().name("host-counters").start(() -> read(process, samples));
			return new HostCounters(samples, process);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Counters a test has sampled.
	 * @param samples the samples
	 * @return the counters
	 */
	static HostCounters of(HostSamples samples) {
		return new HostCounters(samples, null);
	}

	/**
	 * The means over the samples whose second lies inside a window.
	 * @param fromMs the window's start, host clock, in milliseconds
	 * @param toMs its end
	 * @return the means ({@link HostSamples#window}); empty without a sample inside
	 */
	Optional<ObjectNode> window(double fromMs, double toMs) {
		return this.samples.window(fromMs, toMs);
	}

	/**
	 * Stops reading the counters.
	 */
	@Override
	public void close() {
		if (this.process != null) {
			this.process.destroyForcibly();
		}
	}

	/**
	 * A script as PowerShell's {@code -EncodedCommand} takes it: base64 of its UTF-16LE.
	 * @param script the script
	 * @return the encoded script
	 */
	static String encoded(String script) {
		return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
	}

	// each line PowerShell prints, until it is stopped
	private static void read(Process process, HostSamples samples) {
		try (BufferedReader lines = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			lines.lines().forEach(samples::add);
		}
		catch (IOException | UncheckedIOException ex) {
			// stopped: the samples read so far stay
		}
	}

	private static String script() {
		try (InputStream in = HostCounters.class.getResourceAsStream("host-counters.ps1")) {
			if (in == null) {
				throw new IllegalStateException("missing resource host-counters.ps1");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
