package io.archsim.engine.kernel;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * An event that happens with a value.
 *
 * @param <T> the value's type
 */
public abstract class ValueEvent<T> extends Event {

	private @Nullable T value;

	ValueEvent(Simulation simulation) {
		super(simulation);
	}

	/**
	 * The value the event happened with.
	 * @return the value
	 * @throws IllegalStateException before the event is triggered
	 */
	public final T value() {
		T known = this.value;
		if (known == null) {
			throw new IllegalStateException("the event has no value until it is triggered");
		}
		return known;
	}

	/**
	 * Continues with the event's value once it has happened (see {@link #then}).
	 * @param next what to do with the value
	 */
	public final void thenAccept(Consumer<? super T> next) {
		then(() -> next.accept(value()));
	}

	final void succeedWith(T value) {
		trigger();
		this.value = value;
	}

}
