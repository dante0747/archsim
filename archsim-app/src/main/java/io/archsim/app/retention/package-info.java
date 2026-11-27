/**
 * Rows the server keeps only for a while: each feature that stores such rows says which,
 * and how to delete them, in a {@link Retention}, and the API role prunes them every hour
 * (ADR-0111).
 */
@NullMarked
package io.archsim.app.retention;

import org.jspecify.annotations.NullMarked;
