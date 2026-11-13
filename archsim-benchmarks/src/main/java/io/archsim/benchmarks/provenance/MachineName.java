package io.archsim.benchmarks.provenance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The short name of the machine a run measured on: {@code BENCH_MACHINE} when set, else
 * the CPU's model without its vendor's words
 * ({@code 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz} is {@code i7-1165g7}). Never
 * the host's name, which can identify a person.
 */
public final class MachineName {

	/** The variable that names the machine instead of its CPU. */
	static final String VARIABLE = "BENCH_MACHINE";

	/**
	 * The clock speed, trademarks, a generation and vendor words; a word inside a model's
	 * name stays.
	 */
	private static final Pattern VENDOR_WORDS = Pattern.compile(
			"@.*|\\(r\\)|\\(tm\\)|\\b\\d+(st|nd|rd|th) gen\\b|(?<![\\w-])(intel|amd|core|cpu|processor)(?![\\w-])");

	private static final Pattern WORD = Pattern.compile("\\S+");

	private static final Path CPU_INFO = Path.of("/proc/cpuinfo");

	private static final String MODEL_NAME = "model name";

	private MachineName() {
	}

	/**
	 * This machine's name, from the process's environment and its CPU.
	 * @return the name
	 */
	public static String current() {
		return id(System.getenv(), cpu());
	}

	/**
	 * A machine's name.
	 * @param environment the environment, which may name the machine
	 * @param cpu the CPU's model, as the operating system names it
	 * @return {@code BENCH_MACHINE}, else the CPU's model in lower case without its
	 * vendor's words, its words joined by hyphens; {@code unknown} when nothing is left
	 */
	static String id(Map<String, String> environment, String cpu) {
		String named = environment.get(VARIABLE);
		if (named != null && !named.isEmpty()) {
			return named;
		}
		String model = VENDOR_WORDS.matcher(cpu.toLowerCase(Locale.ROOT)).replaceAll(" ");
		String words = WORD.matcher(model).results().map(MatchResult::group).collect(Collectors.joining("-"));
		return words.isEmpty() ? "unknown" : words;
	}

	/**
	 * The CPU's model as the operating system names it: Windows' processor name, Linux's
	 * first {@code model name}, else the architecture. The Python harness fell back to
	 * {@code platform.processor()} instead ({@code Intel64 Family 6 ...} on Windows), so
	 * on a machine that names neither the two harnesses give different IDs.
	 * @return the model
	 */
	static String cpu() {
		String architecture = System.getProperty("os.arch", "");
		if (System.getProperty("os.name", "").startsWith("Windows")) {
			String name = windowsName();
			return name.isEmpty() ? architecture : name;
		}
		if (Files.isReadable(CPU_INFO)) {
			try {
				return Files.readAllLines(CPU_INFO, StandardCharsets.UTF_8)
					.stream()
					.filter((line) -> line.startsWith(MODEL_NAME) && line.contains(":"))
					.map((line) -> line.substring(line.indexOf(':') + 1).strip())
					.findFirst()
					.orElse(architecture);
			}
			catch (IOException ex) {
				return architecture;
			}
		}
		return architecture;
	}

	// what Windows names the processor; empty when PowerShell cannot say
	private static String windowsName() {
		ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-Command",
				"(Get-CimInstance Win32_Processor).Name")
			.redirectError(ProcessBuilder.Redirect.DISCARD);
		try {
			Process powershell = builder.start();
			String name;
			try (InputStream output = powershell.getInputStream()) {
				name = new String(output.readAllBytes(), StandardCharsets.UTF_8).strip();
			}
			return (powershell.waitFor() == 0) ? name : "";
		}
		catch (IOException ex) {
			return "";
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return "";
		}
	}

}
