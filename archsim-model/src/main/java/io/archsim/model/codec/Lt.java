package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The number is below {@code value} (pydantic's {@code lt}; JSON Schema's
 * {@code exclusiveMaximum}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface Lt {

	/**
	 * The value the number must be below.
	 * @return the exclusive upper bound
	 */
	double value();

}
