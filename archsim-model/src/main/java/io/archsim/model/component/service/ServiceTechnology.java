package io.archsim.model.component.service;

import io.archsim.model.codec.JsonEnum;

/** The runtime or framework a service is built with: it sets the concurrency model, GC pauses, JIT warm-up and cold starts. */
public enum ServiceTechnology implements JsonEnum {
    GENERIC("generic"),
    JAVA("java"),
    NODEJS("nodejs"),
    GO("go"),
    PYTHON("python"),
    DOTNET("dotnet"),
    RUST("rust"),
    PHP("php"),
    RUBY("ruby");

    private final String json;

    ServiceTechnology(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
