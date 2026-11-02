/**
 * Price snapshots: versioned on-demand prices of a provider and region, and the instance
 * types they price (format {@code archsim.prices/1}). The model reads them to check the
 * prices a design names (ADR-0026); choosing instance types and costing a design is the
 * engine's.
 */
@NullMarked
package io.archsim.model.pricing;

import org.jspecify.annotations.NullMarked;
