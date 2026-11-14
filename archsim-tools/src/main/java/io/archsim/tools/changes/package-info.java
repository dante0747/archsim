/**
 * Which CI jobs a change needs: the jobs that one of its changed files can affect. It
 * uses the JDK alone, so that CI's {@code changes} job runs it from source before any
 * build.
 */
@NullMarked
package io.archsim.tools.changes;

import org.jspecify.annotations.NullMarked;
