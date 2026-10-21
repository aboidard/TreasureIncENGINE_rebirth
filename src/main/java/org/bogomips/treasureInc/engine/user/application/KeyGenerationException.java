package org.bogomips.treasureInc.engine.user.application;

/** No unused public key could be generated within the allowed number of attempts. */
public class KeyGenerationException extends RuntimeException {
    public KeyGenerationException() {
        super("Could not generate a unique public key");
    }
}
