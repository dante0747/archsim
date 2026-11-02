/**
 * Upgrading a design document from an older schema version to the current one (ADR-0023):
 * {@link io.archsim.model.upgrade.Upgrades#toCurrent} runs the chain of
 * {@link io.archsim.model.upgrade.Upgrade} steps on the document's JSON.
 */
@NullMarked
package io.archsim.model.upgrade;

import org.jspecify.annotations.NullMarked;
