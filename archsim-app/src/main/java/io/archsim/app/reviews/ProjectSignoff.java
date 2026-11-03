package io.archsim.app.reviews;

import io.archsim.app.projects.ProjectDesign;

/**
 * A sign-off with its project, as saved now: what showing it needs.
 *
 * @param signoff the sign-off
 * @param project its project
 */
record ProjectSignoff(StoredSignoff signoff, ProjectDesign project) {

}
