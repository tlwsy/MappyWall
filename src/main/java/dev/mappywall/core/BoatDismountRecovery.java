package dev.mappywall.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Models the bounded transition from riding a boat to stable navigation.
 */
public final class BoatDismountRecovery {
    private static final int REQUEST_INTERVAL_TICKS = 10;
    private static final int DETACHED_CONFIRM_TICKS = 3;
    private static final int EGRESS_STALL_TICKS = 6;
    private static final int SETTLE_TICKS = 3;
    private static final int BOARDING_SUPPRESSION_TICKS = 60;
    private static final int RECOVERY_TIMEOUT_TICKS = 100;
    private static final int NO_BOAT_ENTITY_ID = -1;

    public enum Phase {
        IDLE,
        REQUESTING,
        CLEARING_BOAT,
        SETTLING
    }

    public enum Action {
        NONE,
        REQUEST_DISMOUNT,
        HOLD,
        STEER_EGRESS,
        PULSE_JUMP,
        COMPLETE_REPLAN,
        FAILED
    }

    public record Observation(
            boolean passenger,
            boolean ridingOriginalBoat,
            boolean originalBoatPresent,
            boolean touchingOriginalBoat,
            boolean stableBlockSupport,
            boolean safeWater,
            boolean serverPositionChanged,
            boolean egressAvailable,
            boolean egressProgress
    ) {}

    public record RequestPosition(double x, double y, double z) {
        public RequestPosition {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("request position must be finite");
            }
        }
    }

    private Phase phase = Phase.IDLE;
    private int originalBoatEntityId = NO_BOAT_ENTITY_ID;
    private RequestPosition requestPosition;
    private int requestCooldownTicks;
    private int detachedConfirmTicks;
    private int egressStallTicks;
    private int settleTicks;
    private int boardingSuppressionTicks;
    private int activeTicks;
    private boolean jumpPulseEmitted;

    public void begin(int boatEntityId, double x, double y, double z) {
        validateEntityId(boatEntityId);
        RequestPosition requestedPosition = new RequestPosition(x, y, z);
        if (active()) {
            if (boatEntityId == originalBoatEntityId) {
                return;
            }
            throw new IllegalStateException("recovery already active for another boat");
        }

        originalBoatEntityId = boatEntityId;
        requestPosition = requestedPosition;
        phase = Phase.REQUESTING;
        boardingSuppressionTicks = BOARDING_SUPPRESSION_TICKS;
        clearRecoveryCounters();
    }

    public Action tick(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        advanceBoardingSuppression();
        if (!active()) {
            return Action.NONE;
        }

        activeTicks++;
        if (requestCooldownTicks > 0) {
            requestCooldownTicks--;
        }
        if (activeTicks >= RECOVERY_TIMEOUT_TICKS) {
            return terminate(Action.FAILED);
        }

        if (observation.passenger()) {
            return tickPassenger(observation);
        }
        return tickDetached(observation);
    }

    public boolean active() {
        return phase != Phase.IDLE;
    }

    public Phase phase() {
        return phase;
    }

    public int originalBoatEntityId() {
        return originalBoatEntityId;
    }

    public Optional<RequestPosition> requestPosition() {
        return Optional.ofNullable(requestPosition);
    }

    public boolean suppressBoarding(int entityId) {
        validateEntityId(entityId);
        return boardingSuppressionTicks > 0 && entityId == originalBoatEntityId;
    }

    public void cancel() {
        if (active()) {
            phase = Phase.IDLE;
            clearRecoveryCounters();
        }
    }

    public void reset() {
        phase = Phase.IDLE;
        originalBoatEntityId = NO_BOAT_ENTITY_ID;
        requestPosition = null;
        boardingSuppressionTicks = 0;
        clearRecoveryCounters();
    }

    private Action tickPassenger(Observation observation) {
        if (!observation.ridingOriginalBoat()) {
            return terminate(Action.FAILED);
        }

        phase = Phase.REQUESTING;
        detachedConfirmTicks = 0;
        egressStallTicks = 0;
        settleTicks = 0;
        if (requestCooldownTicks == 0) {
            requestCooldownTicks = REQUEST_INTERVAL_TICKS;
            return Action.REQUEST_DISMOUNT;
        }
        return Action.HOLD;
    }

    private Action tickDetached(Observation observation) {
        if (phase == Phase.REQUESTING) {
            detachedConfirmTicks++;
            if (!observation.serverPositionChanged()
                    && detachedConfirmTicks < DETACHED_CONFIRM_TICKS) {
                return Action.HOLD;
            }
            phase = Phase.CLEARING_BOAT;
        }

        if (observation.touchingOriginalBoat()) {
            return tickBoatContact(observation);
        }

        egressStallTicks = 0;
        boolean stable = observation.stableBlockSupport() || observation.safeWater();
        if (!stable) {
            phase = Phase.CLEARING_BOAT;
            settleTicks = 0;
            return Action.HOLD;
        }

        phase = Phase.SETTLING;
        settleTicks++;
        if (settleTicks >= SETTLE_TICKS) {
            return terminate(Action.COMPLETE_REPLAN);
        }
        return Action.HOLD;
    }

    private Action tickBoatContact(Observation observation) {
        phase = Phase.CLEARING_BOAT;
        settleTicks = 0;
        if (!observation.egressAvailable()) {
            egressStallTicks = 0;
            return Action.HOLD;
        }
        if (observation.egressProgress()) {
            egressStallTicks = 0;
            return Action.STEER_EGRESS;
        }

        egressStallTicks++;
        if (!jumpPulseEmitted && egressStallTicks >= EGRESS_STALL_TICKS) {
            jumpPulseEmitted = true;
            return Action.PULSE_JUMP;
        }
        return Action.STEER_EGRESS;
    }

    private Action terminate(Action action) {
        phase = Phase.IDLE;
        clearRecoveryCounters();
        return action;
    }

    private void advanceBoardingSuppression() {
        if (boardingSuppressionTicks > 0) {
            boardingSuppressionTicks--;
        }
    }

    private void clearRecoveryCounters() {
        requestCooldownTicks = 0;
        detachedConfirmTicks = 0;
        egressStallTicks = 0;
        settleTicks = 0;
        activeTicks = 0;
        jumpPulseEmitted = false;
    }

    private static void validateEntityId(int entityId) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entity id must be non-negative");
        }
    }
}
