/**
 * How the engine reads a part's settings where the Python engine read them from a design
 * that may not be valid yet (ADR-0038): leniently, from the config with its defaults when
 * it is valid and as written otherwise; and a service's time per request by load.
 */
@NullMarked
package io.archsim.engine.settings;

import org.jspecify.annotations.NullMarked;
