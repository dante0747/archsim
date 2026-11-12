package io.archsim.app.shares;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MaxLength;

/**
 * What {@code POST /runs/{id}/shares} takes.
 *
 * @param title shown on the page and in link previews (default: the project's name)
 * @param expiresInDays days until the link stops working ({@code null}: never)
 */
@IgnoreUnknownKeys
record ShareCreate(@MaxLength(ShareCreate.MAX_TITLE) @Nullable String title,
		@Default("30") @Ge(1) @Le(365) @Nullable Integer expiresInDays) {

	/** The longest title a share has. */
	static final int MAX_TITLE = 200;

}
