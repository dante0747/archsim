/**
 * The repository's tooling, in Java (ADR-0072): the commands Gradle runs. The commit
 * messages' check ({@code commits}) and CI's changed parts ({@code changes}) use the JDK
 * alone and run from their source; the price refresh ({@code prices}) runs here.
 */
@NullMarked
package io.archsim.tools;

import org.jspecify.annotations.NullMarked;
