/**
 * The harness of the black-box API tests: a client that speaks HTTP to an API server, the
 * servers of each {@link io.archsim.apitests.Profile} (given or started), a stand-in
 * GitHub for sign-in, and SQL on a server's database. The tests themselves are in the
 * {@code apiTest} source set; {@link io.archsim.apitests.ApiTest} marks their classes.
 */
@NullMarked
package io.archsim.apitests;

import org.jspecify.annotations.NullMarked;
