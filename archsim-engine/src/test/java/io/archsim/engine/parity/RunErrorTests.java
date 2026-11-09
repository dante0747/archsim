package io.archsim.engine.parity;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.model.issue.DocumentInvalidException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A failed run as the baselines record one: the frozen engine's error type and its
 * message, so a Java run failing as Python's did replays it exactly.
 */
class RunErrorTests {

	@Test
	void of_aLimitReached_isPythonsSimulationLimitError() {
		assertThat(RunError.of(new SimulationLimitException("the run went over 2000000 requests"))
			.path("error")
			.stringValue()).isEqualTo("SimulationLimitError: the run went over 2000000 requests");
	}

	@Test
	void of_aDocumentTheEngineCannotRead_isPythonsDocumentInvalidError() {
		DocumentInvalidException invalid = new DocumentInvalidException(List.of());

		assertThat(RunError.of(invalid).path("error").stringValue())
			.isEqualTo("DocumentInvalidError: " + invalid.getMessage());
	}

	@Test
	void of_anyOtherFailure_isNamedByItsJavaType() {
		assertThat(RunError.of(new IllegalStateException("no such part")).path("error").stringValue())
			.isEqualTo("IllegalStateException: no such part");
	}

	@Test
	void of_aFailureWithoutAMessage_isItsTypeAndAnEmptyMessage() {
		assertThat(RunError.of(new NullPointerException()).path("error").stringValue())
			.isEqualTo("NullPointerException: ");
	}

}
