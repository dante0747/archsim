package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;

import io.archsim.engine.runtime.Request.Arrival;
import io.archsim.model.document.RequestType;

import static org.assertj.core.api.Assertions.assertThat;

/** What a request carries of the parts that refused it (ADR-0082). */
class RequestTests {

	private final Request request = Request.withoutTrace(new Arrival(1, RequestType.READ, 0, 0.0, true));

	@Test
	void addRefusal_aPartThatHasNotRefusedIt_isNew() {
		assertThat(this.request.addRefusal("cache", 5.0)).isTrue();
	}

	@Test
	void addRefusal_aPartThatRefusedItBefore_isNotNewAndKeepsTheFirstTime() {
		this.request.addRefusal("cache", 5.0);

		boolean added = this.request.addRefusal("cache", 9.0);

		assertThat(added).isFalse();
		assertThat(this.request.removeRefusal("cache")).hasValue(5.0);
	}

	@Test
	void removeRefusal_aPartThatRefusedIt_forgetsIt() {
		this.request.addRefusal("cache", 5.0);

		this.request.removeRefusal("cache");

		assertThat(this.request.removeRefusal("cache")).isEmpty();
	}

	@Test
	void removeRefusal_aPartThatNeverRefusedIt_isEmpty() {
		this.request.addRefusal("other", 5.0);

		assertThat(this.request.removeRefusal("cache")).isEmpty();
	}

}
