/**
 * The release workflow's check of its tag (ADR-0077): that the tag names the checkout's
 * version, and that docker-compose.yml runs the images the workflow publishes. It uses
 * the JDK alone, so that the workflow runs it from source before any build.
 */
@NullMarked
package io.archsim.tools.release;

import org.jspecify.annotations.NullMarked;
