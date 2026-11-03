package io.archsim.app.shares;

import java.time.Clock;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Access;
import io.archsim.app.access.Visitor;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.store.Times;
import io.archsim.app.web.ApiException;

/**
 * Share links opened by anyone, without signing in, and limited per address. A link that
 * never existed, expired or was revoked is the same {@code 404}.
 */
@Service
class PublicShares {

	private final ShareRepository shares;

	private final RateLimits limits;

	private final Clock clock;

	PublicShares(ShareRepository shares, RateLimits limits, Clock clock) {
		this.shares = shares;
		this.limits = limits;
		this.clock = clock;
	}

	/**
	 * Counts a page opened from an address.
	 * @param visitor where the request comes from
	 * @throws ApiException {@code 429} after many pages in a minute
	 */
	void admit(Visitor visitor) {
		this.limits.enforce("ip:" + visitor.ipOrUnknown() + ":shares", RateLimit.PUBLIC_VIEWS_PER_IP,
				"Too many share pages opened from your address.");
	}

	/**
	 * A shared report.
	 * @param slug the link's id
	 * @param counted whether this opening counts as a view (a link preview does not)
	 * @return the report
	 * @throws ApiException {@code 404} for a link that does not work
	 */
	PublicShareView open(String slug, boolean counted) {
		StoredShare share = active(slug);
		ObjectNode payload = share.payloadOrEmpty();
		if (counted) {
			this.shares.countView(share.id());
		}
		return new PublicShareView(share.slug(), share.title(), share.sharedProjectName(),
				text(payload.path("runLabel")), text(payload.path("scenarioName")), share.createdAt(),
				share.expiresAt(), share.payloadVersion(), share.reportVersion(), share.resultDigest(),
				objectOrEmpty(payload.path("objectives")), objectOrEmpty(payload.path("report")),
				payload.path("document").isObject());
	}

	/**
	 * The share a working link opens, with its payload.
	 * @param slug the link's id
	 * @return the share
	 * @throws ApiException {@code 404} for a link that never existed, expired or was
	 * revoked
	 */
	StoredShare active(String slug) {
		if (!Slugs.looksLikeOne(slug)) {
			throw gone();
		}
		return this.shares.withSlug(slug)
			.filter((share) -> share.payload() != null && share.works(Times.now(this.clock)))
			.orElseThrow(PublicShares::gone);
	}

	private static @Nullable String text(JsonNode value) {
		return value.isString() ? value.stringValue() : null;
	}

	private static JsonNode objectOrEmpty(JsonNode value) {
		return value.isObject() ? value : JsonNodeFactory.instance.objectNode();
	}

	private static ApiException gone() {
		return Access.notFound("Share link");
	}

}
