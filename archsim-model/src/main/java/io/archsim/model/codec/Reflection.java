package io.archsim.model.codec;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

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
