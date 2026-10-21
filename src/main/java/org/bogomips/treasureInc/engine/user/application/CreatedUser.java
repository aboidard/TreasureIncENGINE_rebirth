package org.bogomips.treasureInc.engine.user.application;

import org.bogomips.treasureInc.engine.user.domain.UserInfo;

/** A freshly created user together with its private key, which is only ever available at creation. */
public record CreatedUser(UserInfo user, String privateKey) {
}
