package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The string matches a regular expression (pydantic's {@code pattern}). As in pydantic
 * the expression is searched for, so anchor it with {@code ^...$}; {@code $} is the very
 * end of the text and {@code \d} any Unicode digit, as in the Rust engine pydantic uses.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface Matches {

	/** The expression, spelled as in the Python model (error messages quote it). */
	String value();

}
