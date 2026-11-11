package io.archsim.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import picocli.CommandLine.Model.CommandSpec;

import io.archsim.engine.calibration.mapping.MappingException;
import io.archsim.engine.calibration.mapping.ValidationMessages;
import io.archsim.engine.calibration.prometheus.PrometheusException;
import io.archsim.engine.files.PythonFileException;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.yaml.YamlKeyException;
import io.archsim.engine.yaml.YamlScalarException;
import io.archsim.engine.yaml.YamlValueException;
import io.archsim.model.codec.BindingException;
import io.archsim.model.issue.DocumentInvalidException;

/**
 * A calibration attempted as the Python CLI attempted it: what goes wrong (a design that
 * cannot be used, a replay over its limits, a Prometheus that fails, a file, mapping or
 * range that cannot be read) refused on standard error after {@code Cannot calibrate:},
 * in the Python CLI's words.
 */
final class CalibrationAttempt {

	private static final String CANNOT = "Cannot calibrate: ";

	private CalibrationAttempt() {
	}

	/**
	 * Runs a calibration, refusing what goes wrong.
	 * @param <T> what the calibration gives
	 * @param spec the command, whose standard output and error are printed on
	 * @param calibration the calibration
	 * @param afterLimit the lines that follow a replay over its limits
	 * @return what it gave; empty when it was refused
	 */
	static <T> Optional<T> run(CommandSpec spec, Supplier<T> calibration, List<String> afterLimit) {
		PrintWriter stderr = spec.commandLine().getErr();
		try {
			return Optional.of(calibration.get());
		}
		catch (DocumentInvalidException ex) {
			stderr.println(CANNOT + "document is invalid");
			IssueLines.of(ex.issues()).forEach(spec.commandLine().getOut()::println);
		}
		catch (SimulationLimitException ex) {
			stderr.println(CANNOT + "the replay hit a limit: " + ex.getMessage());
			afterLimit.forEach(stderr::println);
		}
		catch (PrometheusException ex) {
			stderr.println(CANNOT + ex.getMessage());
			ex.detail()
				.filter((said) -> !said.isEmpty())
				.ifPresent((said) -> stderr.println("  Prometheus said: " + said));
		}
		catch (BindingException ex) {
			stderr.println(CANNOT + ValidationMessages.of(ex));
		}
		catch (MappingException | IllegalArgumentException | YamlValueException | YamlKeyException | YamlScalarException
				| PythonFileException ex) {
			stderr.println(CANNOT + ex.getMessage());
		}
		return Optional.empty();
	}

}
