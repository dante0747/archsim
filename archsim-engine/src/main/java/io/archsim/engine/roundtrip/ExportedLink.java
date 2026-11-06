package io.archsim.engine.roundtrip;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One outgoing link as an export wrote it, {@code target[/port][:types][!flag...]}, read
 * back.
 *
 * @param target the part it leads to
 * @param port the port it leaves from; {@code null} when it does not say
 * @param requestTypes the request types it carries; {@code null} when it does not say
 * @param settings the link settings its flags turn on ({@code readsOnMiss},
 * {@code continueOnFailure}), in order
 */
public record ExportedLink(String target, @Nullable String port, @Nullable List<String> requestTypes,
		Map<String, Boolean> settings) {

	/**
	 * Copies the request types and the settings, so the record does not change.
	 */
	public ExportedLink {
		requestTypes = (requestTypes != null) ? List.copyOf(requestTypes) : null;
		settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
	}

}
