package io.archsim.app.tokens;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code POST /tokens} takes.
 *
 * @param name what the token is for, such as {@code CI}
 * @param scope {@code read}: viewer everywhere; {@code write}: your own role
 * @param workspaceId the one workspace it is limited to (default: all yours)
 * @param expiresInDays days until it stops working ({@code null}: never)
 */
@IgnoreUnknownKeys
record TokenCreate(@MinLength(1) @MaxLength(100) String name, @Default("write") TokenScope scope,
		@MaxLength(32) @Nullable String workspaceId, @Default("90") @Ge(1) @Le(3650) @Nullable Integer expiresInDays) {

}
