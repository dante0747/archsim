package io.archsim.cli;

import java.util.List;

import io.archsim.engine.calibration.prometheus.RecordedPrometheus;
import io.archsim.engine.calibration.prometheus.RecordingPrometheus;
import io.archsim.engine.calibration.prometheus.http.HttpPrometheus;
import io.archsim.engine.calibration.prometheus.http.PrometheusServer;
import io.archsim.engine.files.PythonFileException;
import io.archsim.engine.files.PythonFiles;
import io.archsim.model.text.PythonJson;

/**
 * Where {@code archsim calibrate prometheus} reads its responses from, as the Python CLI
 * read them: a server, on the open policy, or responses recorded earlier; and a recording
 * of the responses, written as the Python engine saved one.
 */
final class PrometheusClients {

	/** A recording is written indented by one space, as the Python engine saved it. */
	private static final int RECORDING_INDENT = 1;

	private PrometheusClients() {
	}

	/**
	 * A server, called as the Python CLI's {@code HttpPrometheus(url)} called it: the
	 * open policy, any host at any address, with the default deadline and size cap
	 * (ADR-0057).
	 * @param url its base URL
	 * @return the client, to close once the calibration is done
	 */
	static HttpPrometheus server(String url) {
		return HttpPrometheus.of(new PrometheusServer(url, PrometheusServer.DEFAULT_TIMEOUT_SECONDS,
				PrometheusServer.DEFAULT_MAX_BYTES, List.of(), false));
	}

	/**
	 * Responses recorded earlier, named after their file unless they name their source.
	 * @param file the recording, as given
	 * @return the client
	 * @throws PythonFileException when the file cannot be read or is not JSON
	 */
	static RecordedPrometheus recorded(String file) {
		return RecordedPrometheus.of(PythonFiles.readJson(file), PythonFiles.fileName(file));
	}

	/**
	 * Writes the responses recorded, as the Python engine's {@code save} wrote them.
	 * @param recording the responses
	 * @param file where to write them
	 * @throws CommandException when the file cannot be written
	 */
	static void save(RecordingPrometheus recording, String file) {
		CommandFiles.write(file, PythonJson.writeIndented(recording.recording(), RECORDING_INDENT) + "\n");
	}

}
