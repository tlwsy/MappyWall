package dev.mappywall.core;

import java.util.Objects;
import java.util.OptionalInt;

public final class BoatAcquisitionPolicy {
    public static final int HELD_ITEM_CONFIRM_TIMEOUT_TICKS = 20;
    public static final int REQUEST_VALIDITY_TIMEOUT_TICKS = 40;
    public static final int PLACEMENT_RETRY_BACKOFF_TICKS = 40;
    public static final int ENTITY_CONFIRM_TIMEOUT_TICKS = 40;
    public static final int BOARD_CONFIRM_TIMEOUT_TICKS = 30;
    public static final int BOARD_RETRY_INTERVAL_TICKS = 10;
    public static final int MAX_REJECTED_PLACEMENTS = 2;
    public static final int MAX_BOARD_ATTEMPTS = 3;

    public enum Phase {
        IDLE,
        REQUESTING_SELECTION,
        AWAITING_HELD_ITEM,
        REQUESTING_PLACEMENT,
        PLACEMENT_BACKOFF,
        AWAITING_ENTITY,
        REQUESTING_BOARDING,
        AWAITING_PASSENGER,
        FALLBACK
    }

    public enum Action {
        NONE,
        SELECT_CARRIED_BOAT,
        PLACE_HELD_BOAT,
        BOARD_SELECTED_BOAT
    }

    public record Decision(Action action, OptionalInt boatEntityId) {
        public Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(boatEntityId, "boatEntityId");
            if (boatEntityId.isPresent() && boatEntityId.getAsInt() < 0) {
                throw new IllegalArgumentException("boat entity id must be non-negative");
            }
            if ((action == Action.BOARD_SELECTED_BOAT) != boatEntityId.isPresent()) {
                throw new IllegalArgumentException("only boarding decisions require an entity id");
            }
        }
    }

    public record Observation(
            boolean eligibleWaterRun,
            boolean transactionAvailable,
            boolean passengerInBoat,
            boolean heldBoatConfirmed,
            boolean carriedBoatAvailable,
            boolean placementSurfaceAvailable,
            boolean routeAdjacentBoatAvailable,
            OptionalInt nearbyBoatEntityId,
            OptionalInt newlySpawnedBoatEntityId,
            boolean selectedBoatPresent
    ) {
        public Observation {
            Objects.requireNonNull(nearbyBoatEntityId, "nearbyBoatEntityId");
            Objects.requireNonNull(newlySpawnedBoatEntityId, "newlySpawnedBoatEntityId");
            if (nearbyBoatEntityId.isPresent() && nearbyBoatEntityId.getAsInt() < 0) {
                throw new IllegalArgumentException("nearby boat id must be non-negative");
            }
            if (newlySpawnedBoatEntityId.isPresent()
                    && newlySpawnedBoatEntityId.getAsInt() < 0) {
                throw new IllegalArgumentException("new boat id must be non-negative");
            }
        }
    }

    private static final Decision NO_DECISION = new Decision(Action.NONE, OptionalInt.empty());

    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int boardingTicks;
    private int rejectedPlacements;
    private int boardAttempts;
    private OptionalInt selectedBoatId = OptionalInt.empty();

    public Decision tick(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!observation.eligibleWaterRun()) {
            resetForWaterRun();
            return NO_DECISION;
        }
        if (observation.passengerInBoat()) {
            resetForWaterRun();
            return NO_DECISION;
        }
        if (phase == Phase.FALLBACK || !observation.transactionAvailable()) {
            return NO_DECISION;
        }
        return switch (phase) {
            case IDLE -> chooseInitialAction(observation);
            case REQUESTING_SELECTION -> tickRequestingSelection(observation);
            case REQUESTING_PLACEMENT -> tickRequestingPlacement(observation);
            case PLACEMENT_BACKOFF -> tickPlacementBackoff(observation);
            case REQUESTING_BOARDING -> tickRequestingBoarding(observation);
            case AWAITING_HELD_ITEM -> tickAwaitingHeldItem(observation);
            case AWAITING_ENTITY -> tickAwaitingEntity(observation);
            case AWAITING_PASSENGER -> tickAwaitingPassenger(observation);
            case FALLBACK -> NO_DECISION;
        };
    }

    public void selectionRequested() {
        requirePhase(Phase.REQUESTING_SELECTION);
        phase = Phase.AWAITING_HELD_ITEM;
        phaseTicks = 0;
    }

    public void placementResult(boolean accepted) {
        requirePhase(Phase.REQUESTING_PLACEMENT);
        if (accepted) {
            phase = Phase.AWAITING_ENTITY;
            phaseTicks = 0;
            return;
        }
        rejectedPlacements++;
        if (rejectedPlacements >= MAX_REJECTED_PLACEMENTS) {
            enterFallback();
        } else {
            phase = Phase.PLACEMENT_BACKOFF;
            phaseTicks = 0;
        }
    }

    public void boardingResult(int entityId, boolean accepted) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must be non-negative");
        }
        requirePhase(Phase.REQUESTING_BOARDING);
        if (selectedBoatId.isEmpty() || selectedBoatId.getAsInt() != entityId) {
            throw new IllegalArgumentException("boarding result does not match selected boat");
        }
        boardAttempts++;
        if (!accepted && boardAttempts >= MAX_BOARD_ATTEMPTS) {
            enterFallback();
            return;
        }
        phase = Phase.AWAITING_PASSENGER;
    }

    public void resetForWaterRun() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        boardingTicks = 0;
        rejectedPlacements = 0;
        boardAttempts = 0;
        selectedBoatId = OptionalInt.empty();
    }

    public Phase phase() {
        return phase;
    }

    public int boardAttempts() {
        return boardAttempts;
    }

    public OptionalInt selectedBoatId() {
        return selectedBoatId;
    }

    private Decision chooseInitialAction(Observation observation) {
        if (observation.nearbyBoatEntityId().isPresent()) {
            return requestBoarding(observation.nearbyBoatEntityId().getAsInt(), true);
        }
        if (observation.routeAdjacentBoatAvailable()) {
            return NO_DECISION;
        }
        if (observation.heldBoatConfirmed() && observation.placementSurfaceAvailable()) {
            phase = Phase.REQUESTING_PLACEMENT;
            phaseTicks = 0;
            return new Decision(Action.PLACE_HELD_BOAT, OptionalInt.empty());
        }
        if (!observation.heldBoatConfirmed() && observation.carriedBoatAvailable()) {
            phase = Phase.REQUESTING_SELECTION;
            phaseTicks = 0;
            return new Decision(Action.SELECT_CARRIED_BOAT, OptionalInt.empty());
        }
        return NO_DECISION;
    }

    private Decision tickRequestingSelection(Observation observation) {
        if (observation.carriedBoatAvailable()) {
            return tickValidRequest(
                    new Decision(Action.SELECT_CARRIED_BOAT, OptionalInt.empty()));
        }
        return tickInvalidRequest();
    }

    private Decision tickRequestingPlacement(Observation observation) {
        if (observation.heldBoatConfirmed() && observation.placementSurfaceAvailable()) {
            return tickValidRequest(
                    new Decision(Action.PLACE_HELD_BOAT, OptionalInt.empty()));
        }
        return tickInvalidRequest();
    }

    private Decision tickPlacementBackoff(Observation observation) {
        phaseTicks++;
        if (phaseTicks < PLACEMENT_RETRY_BACKOFF_TICKS) {
            return NO_DECISION;
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        return chooseInitialAction(observation);
    }

    private Decision tickRequestingBoarding(Observation observation) {
        if (selectedBoatId.isPresent() && observation.selectedBoatPresent()) {
            return tickValidRequest(
                    new Decision(Action.BOARD_SELECTED_BOAT, selectedBoatId));
        }
        return tickInvalidRequest();
    }

    private Decision tickInvalidRequest() {
        phaseTicks++;
        if (phaseTicks >= REQUEST_VALIDITY_TIMEOUT_TICKS) {
            enterFallback();
        }
        return NO_DECISION;
    }

    private Decision tickValidRequest(Decision decision) {
        phaseTicks++;
        if (phaseTicks >= REQUEST_VALIDITY_TIMEOUT_TICKS) {
            enterFallback();
            return NO_DECISION;
        }
        return decision;
    }

    private Decision tickAwaitingHeldItem(Observation observation) {
        if (observation.heldBoatConfirmed()) {
            phase = Phase.IDLE;
            phaseTicks = 0;
            return chooseInitialAction(observation);
        }
        phaseTicks++;
        if (phaseTicks >= HELD_ITEM_CONFIRM_TIMEOUT_TICKS) {
            enterFallback();
        }
        return NO_DECISION;
    }

    private Decision tickAwaitingEntity(Observation observation) {
        if (observation.newlySpawnedBoatEntityId().isPresent()) {
            return requestBoarding(observation.newlySpawnedBoatEntityId().getAsInt(), true);
        }
        phaseTicks++;
        if (phaseTicks >= ENTITY_CONFIRM_TIMEOUT_TICKS) {
            enterFallback();
        }
        return NO_DECISION;
    }

    private Decision tickAwaitingPassenger(Observation observation) {
        if (!observation.selectedBoatPresent()) {
            enterFallback();
            return NO_DECISION;
        }
        boardingTicks++;
        if (boardingTicks >= BOARD_CONFIRM_TIMEOUT_TICKS) {
            enterFallback();
            return NO_DECISION;
        }
        if (boardAttempts < MAX_BOARD_ATTEMPTS
                && boardingTicks % BOARD_RETRY_INTERVAL_TICKS == 0) {
            return requestBoarding(selectedBoatId.orElseThrow(), false);
        }
        return NO_DECISION;
    }

    private Decision requestBoarding(int entityId, boolean newSession) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must be non-negative");
        }
        selectedBoatId = OptionalInt.of(entityId);
        if (newSession) {
            boardingTicks = 0;
            boardAttempts = 0;
        }
        phaseTicks = 0;
        phase = Phase.REQUESTING_BOARDING;
        return new Decision(Action.BOARD_SELECTED_BOAT, selectedBoatId);
    }

    private void enterFallback() {
        phase = Phase.FALLBACK;
        selectedBoatId = OptionalInt.empty();
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + phase);
        }
    }
}
