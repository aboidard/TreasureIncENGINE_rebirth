package org.bogomips.treasureInc.engine.user;

public class LockTimeoutException extends RuntimeException {

    public LockTimeoutException(String publicKey) {
        super("Another request is already processing user " + publicKey + ", try again");
    }
}
