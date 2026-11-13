package io.archsim.benchmarks.probes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PostgreSQL's processes by their titles, each one's CPU so far from
 * {@code /proc/<pid>/stat}. A backend that ends during a pass (a pool closing an idle
 * connection) is missing from the pass's figures, so the backends' total can fall short;
 * the background processes' does not.
 */
public final class PostgresProcesses {

	/** Lists each process's title and its {@code stat} line, a tab between them. */
	static final String SCRIPT = "for d in /proc/[0-9]*; do printf \"%s\\t%s\\n\" "
			+ "\"$(tr \"\\0\" \" \" < \"$d/cmdline\" 2>/dev/null)\" \"$(cat \"$d/stat\" 2>/dev/null)\"; done\n";

	/** A client backend's title: user, database, then the client's address. */
	private static final Pattern BACKEND = Pattern.compile("postgres: \\S+ \\S+ \\d");

	/** {@code utime}, field 14 of a stat line, counted from field 3 after the title. */
	private static final int UTIME = 11;

	/** {@code stime}, field 15. */
	private static final int STIME = 12;

	private PostgresProcesses() {
	}

	/**
	 * A PostgreSQL process by its title.
	 * @param title its title, from its {@code cmdline}
	 * @return {@code backends} (the services' connections), {@code walWriter} or
	 * {@code background}; empty for the probe's own processes (its shell, its
	 * {@code cat}, its {@code psql}'s backend)
	 */
	static Optional<String> kind(String title) {
		String text = title.strip();
		if (!text.startsWith("postgres") || text.contains("[local]")) {
			return Optional.empty();
		}
		if (text.startsWith("postgres: walwriter")) {
			return Optional.of("walWriter");
		}
		return Optional.of(BACKEND.matcher(text).lookingAt() ? "backends" : "background");
	}

	/**
	 * Each PostgreSQL process's kind and CPU, from what {@link #SCRIPT} listed.
	 * @param listed one line per process: its title, a tab, its stat line
	 * @return each one's kind and ticks, by pid, in the listing's order
	 */
	static Map<String, PostgresSnapshot.ProcessTime> of(String listed) {
		Map<String, PostgresSnapshot.ProcessTime> processes = new LinkedHashMap<>();
		for (String line : listed.lines().toList()) {
			int tab = line.indexOf('\t');
			String title = (tab >= 0) ? line.substring(0, tab) : line;
			String stat = (tab >= 0) ? line.substring(tab + 1) : "";
			Optional<String> kind = kind(title);
			if (kind.isEmpty() || !stat.contains(")")) {
				continue;
			}
			int space = stat.indexOf(' ');
			String pid = (space >= 0) ? stat.substring(0, space) : stat;
			String[] fields = stat.substring(stat.lastIndexOf(')') + 1).strip().split("\\s+", -1);
			processes.put(pid, new PostgresSnapshot.ProcessTime(kind.get(),
					Long.parseLong(fields[UTIME]) + Long.parseLong(fields[STIME])));
		}
		return processes;
	}

}
