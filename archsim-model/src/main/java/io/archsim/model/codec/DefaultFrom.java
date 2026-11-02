package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the record's static constant that holds a field's default, for a default that is
 * an object or a non-empty list, such as a distribution (pydantic's
 * {@code default_factory}). The constant must be immutable; the JSON Schema does not show
 * it.
 *
 * <pre>{@code
 * public record CacheConfig(@DefaultFrom("COMMAND_TIME") Distribution serviceTimeMs) {
 *
 *     static final Distribution COMMAND_TIME = new Exponential(0.05);
 * }
 * }</pre>
 *
 * <p>
 * Use {@link Default} for a string, number, boolean or empty collection. The constant is
 * package-private, not private: Error Prone does not count an annotation's reference as a
 * use.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface DefaultFrom {

	/**
	 * The name of the record's static constant that holds the default.
	 * @return the constant's name
	 */
	String value();

}
