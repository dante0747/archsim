package io.archsim.model.upgrade;

import java.io.Serial;

/**
 * A document that cannot be upgraded to the current schema: not an object, no version, a version
 * that is not one, a newer version than this build knows, or no path from its version.
 */
public final class UpgradeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UpgradeException(String message) {
        super(message);
    }
}
