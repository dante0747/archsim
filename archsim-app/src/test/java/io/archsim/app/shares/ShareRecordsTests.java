package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.archsim.app.access.Principal;
import io.archsim.app.support.HistoryEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share links' changes and their lines in the history.
 */
class ShareRecordsTests extends ShareTestSupport {

	@Autowired
	private ShareRecords records;

	@Autowired
	private ShareRepository shares;

	@Test
	void revoke_twiceFromTheSameRead_isRecordedOnce() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String id = created(share(ada, run(project, "succeeded"), "{}")).path("id").asString();
		StoredShare read = this.shares.find(id).orElseThrow();
		Principal principal = Principal.session(ada.userId(), "s", null);

		this.records.revoke(read, principal);
		this.records.revoke(read, principal);

		assertThat(projectHistory(project)).extracting(HistoryEvent::action)
			.containsExactly("share.create", "share.revoke");
	}

}
