package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How the editor shows a field: its label, unit and group, and whether it is an
 * assumption. The JSON Schema carries it as the field's {@code x-ui} object, and the
 * component catalog reads it for the inspector's forms.
 *
 * <p>
 * Every attribute is optional; one left at its default is not written.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Ui {

	/**
	 * The name people see, such as {@code Network latency (one way)}.
	 * @return the label, or empty for the field's name made readable
	 */
	String label() default "";

	/**
	 * The unit of the value, such as {@code ms} or {@code req/s}.
	 * @return the unit, or empty for none
	 */
	String unit() default "";

	/**
	 * The section of the form the field belongs to, such as {@code Reliability}.
	 * @return the group, or empty for none
	 */
	String group() default "";

	/**
	 * Whether the value is something the user asserts (a service time, a hit ratio)
	 * rather than a structural setting, so results are not mistaken for measurements.
	 * @return {@code true} for an assumption
	 */
	boolean assumption() default false;

	/**
	 * Whether the editor shows the field on the part's card, not only in the inspector.
	 * @return {@code true} for a headline setting
	 */
	boolean headline() default false;

	/**
	 * When the field is shown: {@code otherField=value}, such as
	 * {@code hitModel=fixed_ratio} or {@code autoscaling=true}. The value takes the other
	 * field's JSON type.
	 * @return the condition, or empty to always show the field
	 */
	String visibleWhen() default "";

	/**
	 * The step of the form's number input, such as {@code 0.05}.
	 * @return the step, or {@code 0} for the editor's own
	 */
	double step() default 0;

	/**
	 * The form control to use instead of the one the field's type suggests, such as
	 * {@code loadCurve} or {@code faults}.
	 * @return the kind, or empty for the type's own
	 */
	String kind() default "";

	/**
	 * For a field that names another part: the component type it must have, such as
	 * {@code kubernetes_cluster}.
	 * @return the component type, or empty when the field names no part
	 */
	String refType() default "";

	/**
	 * Whether the form leaves the field out (another part of the editor sets it).
	 * @return {@code true} to hide the field
	 */
	boolean hidden() default false;

}
