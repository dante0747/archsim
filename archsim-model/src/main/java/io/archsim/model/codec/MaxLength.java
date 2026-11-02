package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The string has at most {@code value} characters (Unicode code points, as Python
 * counts).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface MaxLength {

	/**
	 * The most characters the string may have.
	 * @return the inclusive maximum length, in code points
	 */
	int value();

}
