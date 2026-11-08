package io.archsim.engine.kernel;

import java.util.ArrayList;
import java.util.List;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Resource.Request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Resources beyond SimPy's recorded programs ({@link QueueCasesTests}).
 */
class ResourceTests {

	private final Simulation simulation = new Simulation();

	@Test
	void constructor_noSlots_isRefusedInSimPysWords() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Resource(this.simulation, 0))
			.withMessage("\"capacity\" must be > 0.");
	}

	@Test
	void cancel_grantedRequest_keepsItsSlot() {
		Resource resource = new Resource(this.simulation, 1);
		Request request = resource.request();

		request.cancel();

		assertThat(resource.count()).isOne();
	}

	@Test
	void cancel_waitingRequest_leavesTheQueue() {
		Resource resource = new Resource(this.simulation, 1);
		resource.request();
		Request waiting = resource.request();

		waiting.cancel();

		assertThat(resource.queued()).isZero();
	}

	@Property
	void request_usersArrivingAndLeaving_neverHoldMoreSlotsThanThereAre(@ForAll @IntRange(min = 1, max = 4) int slots,
			@ForAll @Size(min = 1, max = 60) List<@DoubleRange(min = 0, max = 5) Double> holds) {
		Simulation fresh = new Simulation();
		Resource resource = new Resource(fresh, slots);
		List<Integer> held = new ArrayList<>();
		for (int index = 0; index < holds.size(); index++) {
			double hold = Math.floor(holds.get(index));
			fresh.timeout(index % 3).then(() -> {
				Request request = resource.request();
				request.then(() -> {
					held.add(resource.count());
					fresh.timeout(hold).then(() -> resource.release(request));
				});
			});
		}

		KernelCases.runAll(fresh);

		assertThat(held).hasSameSizeAs(holds).allMatch((count) -> count <= slots);
	}

}
