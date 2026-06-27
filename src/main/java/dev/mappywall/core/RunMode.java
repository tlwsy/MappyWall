package dev.mappywall.core;

public enum RunMode {
    MANUAL(false),
    AUTO_WALK(true),
    AUTO_ELYTRA(true);

    private final boolean automatic;

    RunMode(boolean automatic) {
        this.automatic = automatic;
    }

    public boolean isAutomatic() {
        return automatic;
    }
}

