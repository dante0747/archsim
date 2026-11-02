package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * No item of the list appears twice. A repeat is a {@code value_error} at the list, after its
 * items are valid, as a pydantic field validator reports it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface UniqueItems {
    /** What the error says after {@code "Value error, "}. */
    String value();
}
