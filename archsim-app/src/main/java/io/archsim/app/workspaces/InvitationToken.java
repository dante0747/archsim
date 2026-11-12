package io.archsim.app.workspaces;

import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * An invitation link's token, as {@code /invitations/preview} and {@code /accept} take
 * it.
 *
 * @param token the token from the link
 */
@IgnoreUnknownKeys
record InvitationToken(@MinLength(10) @MaxLength(200) String token) {

}
