package io.archsim.parity;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * The rule that keeps a report the same on every platform (ADR-0050): no class calls one
 * of {@link Math}'s transcendental functions, which may round apart from
 * {@link StrictMath}'s by platform.
 */
public final class StrictMathRule {

	private static final DescribedPredicate<JavaCall<?>> TRANSCENDENTAL_MATH = DescribedPredicate.describe(
			"a transcendental function of Math",
			(call) -> call.getTargetOwner().isEquivalentTo(Math.class) && call.getName()
				.matches("log|log10|log1p|exp|expm1|pow|sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh|cbrt|hypot"));

	private StrictMathRule() {
	}

	/**
	 * The rule, to check against a module's classes.
	 * @return no class calls {@code Math.log}, {@code exp}, {@code pow} or another
	 * function {@code StrictMath} computes the same on every platform
	 */
	public static ArchRule rule() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethodWhere(TRANSCENDENTAL_MATH)
			.because("Math may round them apart by platform, and one seed gives one report on every platform "
					+ "(ADR-0050)");
	}

}
