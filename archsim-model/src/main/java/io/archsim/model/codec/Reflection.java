package io.archsim.model.codec;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Reaching a record's members reflectively, where a failure can only be a bug: its
 * accessors, its canonical constructor and its constants.
 */
final class Reflection {

	private Reflection() {
	}

	static @Nullable Object get(Method accessor, Object record) {
		try {
			return accessor.invoke(record);
		}
		catch (InvocationTargetException ex) {
			throw new IllegalStateException("accessor " + accessor + " failed", ex.getCause());
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException("accessor " + accessor + " is not public", ex);
		}
	}

	/**
	 * The canonical constructor of a record, made accessible.
	 * @param type the record
	 * @param components the record's components, in order
	 * @return the constructor that takes every component
	 */
	static Constructor<?> canonicalConstructor(Class<?> type, RecordComponent[] components) {
		try {
			return accessible(type.getDeclaredConstructor(
					Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new)));
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(type + " has no canonical constructor", ex);
		}
	}

	/**
	 * The value of a static field a type declares, such as a record's constant.
	 * @param type the type that declares the field
	 * @param name the field's name
	 * @return the field's value; empty when the type declares no static field of that
	 * name, or it holds {@code null}
	 */
	static Optional<Object> staticValue(Class<?> type, String name) {
		for (Field field : type.getDeclaredFields()) {
			if (field.getName().equals(name) && Modifier.isStatic(field.getModifiers())) {
				return Optional.ofNullable(staticValue(field));
			}
		}
		return Optional.empty();
	}

	private static @Nullable Object staticValue(Field field) {
		try {
			return accessible(field).get(null);
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException("field " + field + " cannot be read", ex);
		}
	}

	/**
	 * A member made accessible: a record need not be public, as one may be an
	 * implementation detail of its package.
	 * @param <T> the kind of member
	 * @param member a field, method or constructor of a record
	 * @return the same member
	 */
	static <T extends AccessibleObject> T accessible(T member) {
		member.setAccessible(true);
		return member;
	}

}
