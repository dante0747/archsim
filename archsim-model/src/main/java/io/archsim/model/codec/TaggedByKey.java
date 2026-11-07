package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A sealed interface whose records are told apart by which key an object has (a pydantic
 * union with a callable {@code Discriminator} that names a member by a key it finds).
 * Each record names its key with {@link KeyTag}; the first record in {@link #members()}
 * whose key the object has is read, and errors inside it are located under its key. An
 * object with none of the keys, or a value that is not an object, is the union's own
 * error.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TaggedByKey {

	/**
	 * The union's records, in the order their keys are looked for: when an object has the
	 * keys of several, the first is read. Each permitted record, once.
	 * @return the records, in order
	 */
	Class<?>[] members();

	/**
	 * The error's type when no record's key is there (pydantic's
	 * {@code custom_error_type}).
	 * @return the error's type
	 */
	String errorType();

	/**
	 * The error's message when no record's key is there (pydantic's
	 * {@code custom_error_message}).
	 * @return the message
	 */
	String errorMessage();

}
