package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A sealed interface whose records are told apart by a string property (a pydantic union
 * with a {@code discriminator}). Each permitted record names its value with {@link Tag};
 * the order of the {@code permits} clause is the order of the union.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Tagged {

	/** The property that holds the tag, such as {@code kind}. */
	String value();

}
