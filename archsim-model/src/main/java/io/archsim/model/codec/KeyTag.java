package io.archsim.model.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The field whose key picks this record in its {@link TaggedByKey} union, by its JSON
 * name or its Python name, and the tag the record's errors are located under. The key
 * stays in the object, as the record's own field, and the record is written without a
 * tag.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface KeyTag {

	/**
	 * The JSON name of the field that picks the record, which is its tag too.
	 * @return the field's JSON name
	 */
	String value();

}
