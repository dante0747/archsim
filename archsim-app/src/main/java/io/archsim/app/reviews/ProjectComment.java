package io.archsim.app.reviews;

import io.archsim.app.projects.ProjectDesign;

/**
 * A comment with its project, as saved now and with the asker's role there: what showing
 * it needs.
 *
 * @param comment the comment
 * @param project its project
 */
record ProjectComment(StoredComment comment, ProjectDesign project) {

}
