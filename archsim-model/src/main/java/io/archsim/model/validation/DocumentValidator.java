package io.archsim.model.validation;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * Checks a design the way the Python engine's {@code validate_document} does, issue for
 * issue and word for word: ids, component types and configs, links and their ports, the
 * call graph (clients, cycles, how much work one request causes), which request types
 * reach which part, each component type's own rules, faults, the workload and prices.
 * Errors stop a simulation; warnings do not.
 */
public final class DocumentValidator {

	/**
	 * The most component calls one client request may cause by default; above a tenth of
	 * it, a warning.
	 */
	public static final double DEFAULT_MAX_AMPLIFICATION = 10_000;

	private final ComponentRegistry registry;

	private final PriceSnapshots prices;

	/**
	 * A validator that knows these component types and price snapshots.
	 * @param registry the component types a design's nodes may use
	 * @param prices the price snapshots a design's pricing is looked up in
	 */
	public DocumentValidator(ComponentRegistry registry, PriceSnapshots prices) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.prices = Objects.requireNonNull(prices, "prices");
	}

	/**
	 * A validator of ArchSim's own component types and the price snapshots it ships.
	 * @return the standard validator
	 */
	public static DocumentValidator standard() {
		return new DocumentValidator(StandardComponents.registry(), PriceSnapshots.shipped());
	}

	/**
	 * Checks {@code document}, bounding the calls one request may cause at the default.
	 * @param document the design to check
	 * @return every issue found, and what validation worked out on the way
	 */
	public ValidationResult validate(ArchitectureDocument document) {
		return validate(document, DEFAULT_MAX_AMPLIFICATION);
	}

	/**
	 * Checks {@code document}. The checks run in a fixed order, and the issues come in
	 * the order they found them.
	 * @param document the design to check
	 * @param maxAmplification the most component calls one client request may cause;
	 * {@code null} for no bound
	 * @return every issue found, and what validation worked out on the way
	 */
	public ValidationResult validate(ArchitectureDocument document, @Nullable Double maxAmplification) {
		ValidationContext context = new ValidationContext(document, this.registry);
		new IdChecks(context).check();
		new ComponentTypeChecks(context).check();
		new LinkChecks(context).check();
		RequestFlow flow = new CallGraphChecks(context).check(maxAmplification);
		new ComponentRuleChecks(context).check(flow.view());
		new WorkloadChecks(context).check();
		new PricingChecks(context, this.prices).check();
		return new ValidationResult(context.issues(), context.configs(), flow.view(), flow.edgeFlows());
	}

}
