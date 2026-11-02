/**
 * Reading and writing records as JSON with the rules, error messages and JSON Schema of
 * the Python engine's pydantic models (ADR-0022).
 * {@link io.archsim.model.codec.RecordCodec} is the entry point; the annotations in this
 * package declare a record's JSON.
 */
@NullMarked
package io.archsim.model.codec;

import org.jspecify.annotations.NullMarked;
