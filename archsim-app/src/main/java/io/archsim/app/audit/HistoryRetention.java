package io.archsim.app.audit;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.retention.Retention;

/**
 * The history's events older than it keeps them ({@code ARCHSIM_HISTORY_DAYS}, a year): a
 * change is kept that long, with who made it and from which address, then deleted. Events
 * go in the order the history recorded them, by id, which grows with time: a batch looks
 * at the oldest events and deletes those recorded before the cutoff, so a prune reads the
 * events it deletes and one batch more, never the whole history.
 */
@Component
class HistoryRetention implements Retention {

	/** The most events one statement looks at, and so deletes. */
	private static final int BATCH = 1000;

	private final AuditRepository repository;

	private final ArchsimProperties properties;

	HistoryRetention(AuditRepository repository, ArchsimProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	@Override
	public String description() {
		return "history events past their retention";
	}

	@Override
	public int prune(Instant now) {
		Instant cutoff = now.minus(Duration.ofDays(this.properties.historyDays()));
		return Retention.inBatches(() -> this.repository.deleteOldestBefore(cutoff, BATCH));
	}

}
