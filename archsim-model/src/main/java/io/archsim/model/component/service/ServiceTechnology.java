package io.archsim.model.component.service;

import io.archsim.model.codec.JsonEnum;

/**
 * The runtime or framework a service is built with: it sets the concurrency model, GC
 * pauses, JIT warm-up and cold starts.
 */
public enum ServiceTechnology implements JsonEnum {

	/** A runtime of no particular kind. */
	GENERIC("generic"),
	/** Java on the JVM, with GC pauses and JIT warm-up. */
	JAVA("java"),
	/** Node.js, on an event loop. */
	NODEJS("nodejs"),
	/** Go, whose garbage collector pauses briefly. */
	GO("go"),
	/** Python. */
	PYTHON("python"),
	/** .NET, with GC pauses and JIT warm-up. */
	DOTNET("dotnet"),
	/** Rust, with no garbage collector. */
	RUST("rust"),
	/** PHP, with no garbage collection pauses. */
	PHP("php"),
	/** Ruby. */
	RUBY("ruby");

	private final String json;

	ServiceTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
