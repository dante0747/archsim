/**
 * Values as the shared database holds them, made as the Python API makes them: ids, times
 * to the microsecond, and text cut to a column's length in characters. Also the
 * application's clock, which never tells the same microsecond twice (ADR-0041).
 */
@NullMarked
package io.archsim.app.store;

import org.jspecify.annotations.NullMarked;
