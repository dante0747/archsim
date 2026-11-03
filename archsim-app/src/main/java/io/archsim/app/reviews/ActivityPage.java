package io.archsim.app.reviews;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A page of history, newest first.
 *
 * @param items the changes
 * @param nextBefore what to pass as {@code before} for the next, older page; none on the
 * last page
 */
record ActivityPage(List<ActivityItem> items, @Nullable Long nextBefore) {

}
