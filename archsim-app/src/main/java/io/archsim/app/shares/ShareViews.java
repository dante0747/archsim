package io.archsim.app.shares;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.store.Times;

/**
 * Share links as their workspace sees them: with their page's path, the whole link when
 * the server knows its public address ({@code ARCHSIM_PUBLIC_URL}), and whether they
 * still work.
 */
@Component
class ShareViews {

	private final ArchsimProperties properties;

	private final Clock clock;

	ShareViews(ArchsimProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	List<ShareView> of(List<StoredShare> shares) {
		return shares.stream().map(this::of).toList();
	}

	ShareView of(StoredShare share) {
		String path = "/r/" + share.slug();
		String url = this.properties.publicAddress().map((address) -> address + path).orElse(null);
		return new ShareView(share.id(), share.slug(), path, url, share.title(), share.projectId(), share.runId(),
				share.createdBy(), share.createdAt(), share.expiresAt(), share.revokedAt(), share.viewCount(),
				share.resultDigest(), share.reportVersion(), share.status(Times.now(this.clock)));
	}

}
