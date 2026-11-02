package io.archsim.model.codec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;

/** Calling a record's accessor reflectively, where a failure can only be a bug. */
final class Reflection {

	private Reflection() {
	}

	static @Nullable Object get(Method accessor, Object record) {
		try {
			return accessor.invoke(record);
		}
		catch (InvocationTargetException e) {
			throw new IllegalStateException("accessor " + accessor + " failed", e.getCause());
		}
		catch (IllegalAccessException e) {
			throw new IllegalStateException("accessor " + accessor + " is not public", e);
		}
	}

}
