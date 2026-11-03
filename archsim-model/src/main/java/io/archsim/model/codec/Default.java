package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value a field takes when the input leaves it out. A field with neither this,
 * {@link DefaultFrom} nor {@code @Nullable} is required; a {@code @Nullable} field
 * without one defaults to {@code null}.
 *
 * <p>
 * For a {@link String} or {@link JsonEnum} field the value is the string itself
 * ({@code @Default("generic")}); for a number, a boolean or an empty collection it is
 * that value's JSON ({@code @Default("50.0")}, {@code @Default("true")},
 * {@code @Default("[]")}). It is bound like an input, so a default that does not fit its
 * field fails when the codec first meets the record. A record field whose own fields all
 * have defaults commonly takes {@code @Default("{}")}, the record those defaults make;
 * any other object, and a non-empty list, is a {@link DefaultFrom} constant.
 *
 * <p>
 * An empty collection's or an empty object's default is what pydantic calls a
 * {@code default_factory}: the JSON Schema does not show it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Default {

	/**
	 * The default, as the string itself or as its JSON.
	 * @return the default's text
	 */
	String value();

}
