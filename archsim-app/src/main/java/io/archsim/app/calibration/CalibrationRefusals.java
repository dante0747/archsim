package io.archsim.app.calibration;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;

import io.archsim.app.web.ApiException;
import io.archsim.engine.calibration.CalibrationOutcome;
import io.archsim.engine.calibration.mapping.MappingException;
import io.archsim.engine.calibration.mapping.ValidationMessages;
import io.archsim.engine.calibration.prometheus.PrometheusException;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.yaml.YamlKeyException;
import io.archsim.engine.yaml.YamlScalarException;
import io.archsim.engine.yaml.YamlValueException;
import io.archsim.model.codec.BindingException;
import io.archsim.model.issue.DocumentInvalidException;

/**
 * A calibration's failures as the Python API answered them: {@code 422} for a design the
 * engine cannot read, with its issues; the replay-limit words for a replay over its
 * limits; and {@code Cannot calibrate: } with the engine's words for a mapping, a range,
 * telemetry or a Prometheus server that cannot be used ({@code PrometheusError},
 * {@code ValueError}, {@code KeyError} and pydantic's {@code ValidationError} in Python).
 */
final class CalibrationRefusals {

	private static final String NOT_CALIBRATED = "The document cannot be calibrated";

	private CalibrationRefusals() {
	}

	/**
	 * Runs a calibration, refusing its failures as the Python API refused them.
	 * @param calibration the calibration
	 * @return its outcome
	 * @throws ApiException {@code 422} for a failure the Python API refused
	 */
	static CalibrationOutcome run(Supplier<CalibrationOutcome> calibration) {
		try {
			return calibration.get();
		}
		catch (DocumentInvalidException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, NOT_CALIBRATED, ex.issues());
		}
		catch (SimulationLimitException ex) {
			throw unreadable("Cannot calibrate: the replay run hit a limit (" + ex.getMessage()
					+ "). Shorten the design's durationS, lower the load, or calibrate with replay off.");
		}
		catch (BindingException ex) {
			throw unreadable("Cannot calibrate: " + ValidationMessages.of(ex));
		}
		catch (PrometheusException | MappingException | IllegalArgumentException | YamlValueException | YamlKeyException
				| YamlScalarException ex) {
			throw unreadable("Cannot calibrate: " + ex.getMessage());
		}
	}

	/**
	 * A request the API refuses in its own words.
	 * @param message what is wrong
	 * @return the API's {@code 422}
	 */
	static ApiException unreadable(String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message, List.of());
	}

}
