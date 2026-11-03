package io.archsim.app.projects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Two requests at once: a save made from a revision that another save has replaced is
 * refused rather than lost, and a workspace stays locked while its projects are counted.
 * The Python API had both races.
 */
class ProjectRacesTests extends ProjectTestSupport {

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ProjectChanges changes;

	@Autowired
	private ProjectRepository projects;

	@Autowired
	private ProjectRoom room;

	@Test
	void update_afterAnotherSaveGotThereFirst_isAConflict() {
		String id = created(post("/api/v1/projects", "{}")).path("id").asString();
		Project read = this.projects.find(id).orElseThrow();
		jdbc().sql("UPDATE projects SET revision = 2 WHERE id = :id").param("id", id).update();
		Revision revision = new Revision("Late", null, read.document(), read.revision());

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.transactions
				.executeWithoutResult((status) -> this.changes.update(read, revision, Principal.local(null))))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(ex.getMessage())
					.isEqualTo("Project was modified elsewhere (revision 2, you had 1). Reload before saving.");
			});
	}

	@Test
	void check_withinATransaction_locksTheWorkspaceUntilItEnds() {
		workspace("locked1", "sandbox", "owner");
		JdbcClient other = JdbcClient.create(new DriverManagerDataSource(DATABASE));

		this.transactions.executeWithoutResult((status) -> {
			this.room.check("locked1");

			assertThatExceptionOfType(DataAccessException.class)
				.isThrownBy(() -> other.sql("SELECT id FROM workspaces WHERE id = 'locked1' FOR UPDATE NOWAIT")
					.query((rows, number) -> rows.getString(1))
					.list());
		});
	}

}
