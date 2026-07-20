package dev.mappywall.core;

import static dev.mappywall.core.BoatAcquisitionPolicy.Action.BOARD_SELECTED_BOAT;
import static dev.mappywall.core.BoatAcquisitionPolicy.Action.NONE;
import static dev.mappywall.core.BoatAcquisitionPolicy.Action.PLACE_HELD_BOAT;
import static dev.mappywall.core.BoatAcquisitionPolicy.Action.SELECT_CARRIED_BOAT;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.AWAITING_ENTITY;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.AWAITING_HELD_ITEM;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.AWAITING_PASSENGER;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.FALLBACK;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.IDLE;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.PLACEMENT_BACKOFF;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.REQUESTING_BOARDING;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.REQUESTING_PLACEMENT;
import static dev.mappywall.core.BoatAcquisitionPolicy.Phase.REQUESTING_SELECTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class BoatAcquisitionPolicyTest {
    @Test
    void usesReviewedFixedBounds() {
        assertEquals(20, BoatAcquisitionPolicy.HELD_ITEM_CONFIRM_TIMEOUT_TICKS);
        assertEquals(40, BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS);
        assertEquals(40, BoatAcquisitionPolicy.PLACEMENT_RETRY_BACKOFF_TICKS);
        assertEquals(40, BoatAcquisitionPolicy.ENTITY_CONFIRM_TIMEOUT_TICKS);
        assertEquals(30, BoatAcquisitionPolicy.BOARD_CONFIRM_TIMEOUT_TICKS);
        assertEquals(10, BoatAcquisitionPolicy.BOARD_RETRY_INTERVAL_TICKS);
        assertEquals(2, BoatAcquisitionPolicy.MAX_REJECTED_PLACEMENTS);
        assertEquals(3, BoatAcquisitionPolicy.MAX_BOARD_ATTEMPTS);
    }

    @Test
    void nearbyBoatHasPriorityOverHeldAndCarriedBoat() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision decision = policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false));

        assertEquals(BOARD_SELECTED_BOAT, decision.action());
        assertEquals(41, decision.boatEntityId().orElseThrow());
    }

    @Test
    void routeAdjacentBoatDefersInventoryUseUntilItBecomesReachable() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Observation unreachableRouteBoat =
                new BoatAcquisitionPolicy.Observation(
                        true, true, false, true, true, true, true,
                        OptionalInt.empty(), OptionalInt.empty(), false);

        assertEquals(NONE, policy.tick(unreachableRouteBoat).action());
        assertEquals(IDLE, policy.phase());

        BoatAcquisitionPolicy.Decision reachable = policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false));
        assertEquals(BOARD_SELECTED_BOAT, reachable.action());
        assertEquals(41, reachable.boatEntityId().orElseThrow());
    }

    @Test
    void unavailableTransactionsAreDeferredWithoutStartingATimer() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();

        assertEquals(NONE, policy.tick(observation(
                true, false, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false)).action());
        assertEquals(IDLE, policy.phase());
    }

    @Test
    void vanishedSelectionItemAndInvalidatedPlacementSurfaceAreBounded() {
        BoatAcquisitionPolicy selection = new BoatAcquisitionPolicy();
        assertEquals(SELECT_CARRIED_BOAT, selection.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS;
                tick++) {
            assertEquals(NONE, selection.tick(observation(
                    true, true, false, false, false, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
            assertEquals(REQUESTING_SELECTION, selection.phase());
        }
        assertEquals(NONE, selection.tick(observation(
                true, true, false, false, false, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        assertEquals(FALLBACK, selection.phase());

        BoatAcquisitionPolicy placement = new BoatAcquisitionPolicy();
        assertEquals(PLACE_HELD_BOAT, placement.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS;
                tick++) {
            assertEquals(NONE, placement.tick(observation(
                    true, true, false, true, true, false,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
            assertEquals(REQUESTING_PLACEMENT, placement.phase());
        }
        assertEquals(NONE, placement.tick(observation(
                true, true, false, true, true, false,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        assertEquals(FALLBACK, placement.phase());
    }

    @Test
    void carriedBoatSelectionRequiresHeldConfirmationAndTimesOutAtTickTwenty() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(SELECT_CARRIED_BOAT, policy.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        policy.selectionRequested();
        assertEquals(AWAITING_HELD_ITEM, policy.phase());

        for (int tick = 1;
                tick < BoatAcquisitionPolicy.HELD_ITEM_CONFIRM_TIMEOUT_TICKS;
                tick++) {
            assertEquals(NONE, policy.tick(observation(
                    true, true, false, false, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
            assertEquals(AWAITING_HELD_ITEM, policy.phase(), "confirmation tick " + tick);
        }
        assertEquals(NONE, policy.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void heldConfirmationTransitionsToPlacementOnTheSameTick() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(SELECT_CARRIED_BOAT, policy.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        policy.selectionRequested();

        BoatAcquisitionPolicy.Decision placement = policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false));

        assertEquals(PLACE_HELD_BOAT, placement.action());
        assertEquals(REQUESTING_PLACEMENT, policy.phase());
    }

    @Test
    void twoRejectedPlacementsUseExactlyFortyTicksOfBackoffThenEnterFallback() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(PLACE_HELD_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        policy.placementResult(false);
        assertEquals(PLACEMENT_BACKOFF, policy.phase());

        for (int tick = 1;
                tick < BoatAcquisitionPolicy.PLACEMENT_RETRY_BACKOFF_TICKS;
                tick++) {
            assertEquals(NONE, policy.tick(observation(
                    true, true, false, true, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
            assertEquals(PLACEMENT_BACKOFF, policy.phase(), "backoff tick " + tick);
        }

        BoatAcquisitionPolicy.Decision retry = policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false));
        assertEquals(PLACE_HELD_BOAT, retry.action());
        assertEquals(REQUESTING_PLACEMENT, policy.phase());

        policy.placementResult(false);
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void acceptedPlacementRequiresANewEntityAndTimesOutAtTickForty() {
        BoatAcquisitionPolicy policy = beginAcceptedPlacement();
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.ENTITY_CONFIRM_TIMEOUT_TICKS;
                tick++) {
            assertEquals(NONE, policy.tick(observation(
                    true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
            assertEquals(AWAITING_ENTITY, policy.phase(), "entity tick " + tick);
        }

        assertEquals(NONE, policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void newPlacedEntityIsBoardedAndPassengerConfirmationReturnsToIdle() {
        BoatAcquisitionPolicy policy = beginAcceptedPlacement();
        BoatAcquisitionPolicy.Decision board = policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.empty(), OptionalInt.of(52), false));
        assertEquals(BOARD_SELECTED_BOAT, board.action());
        assertEquals(52, board.boatEntityId().orElseThrow());

        policy.boardingResult(52, true);
        assertEquals(AWAITING_PASSENGER, policy.phase());
        assertEquals(NONE, policy.tick(observation(
                true, true, true, false, false, false,
                OptionalInt.empty(), OptionalInt.empty(), true)).action());
        assertEquals(IDLE, policy.phase());
        assertTrue(policy.selectedBoatId().isEmpty());
        assertEquals(0, policy.boardAttempts());
    }

    @Test
    void threeRejectedBoardingAttemptsEnterFallback() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision board = policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false));
        for (int attempt = 1; attempt <= BoatAcquisitionPolicy.MAX_BOARD_ATTEMPTS; attempt++) {
            assertEquals(BOARD_SELECTED_BOAT, board.action());
            policy.boardingResult(41, false);
            if (attempt < BoatAcquisitionPolicy.MAX_BOARD_ATTEMPTS) {
                board = advanceToBoardRetry(policy, 41);
            }
        }
        assertEquals(FALLBACK, policy.phase());
        assertEquals(BoatAcquisitionPolicy.MAX_BOARD_ATTEMPTS, policy.boardAttempts());
        assertTrue(policy.selectedBoatId().isEmpty());
    }

    @Test
    void acceptedBoardingRetriesAtTicksTenAndTwentyThenTimesOutAtTickThirty() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision first = policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false));
        policy.boardingResult(first.boatEntityId().orElseThrow(), true);
        assertEquals(1, policy.boardAttempts());

        assertNoBoardingAction(policy, 1, 9);
        BoatAcquisitionPolicy.Decision second = boardingTick(policy);
        assertEquals(BOARD_SELECTED_BOAT, second.action());
        assertEquals(41, second.boatEntityId().orElseThrow());
        policy.boardingResult(41, true);
        assertEquals(2, policy.boardAttempts());

        assertNoBoardingAction(policy, 11, 19);
        BoatAcquisitionPolicy.Decision third = boardingTick(policy);
        assertEquals(BOARD_SELECTED_BOAT, third.action());
        assertEquals(41, third.boatEntityId().orElseThrow());
        policy.boardingResult(41, true);
        assertEquals(3, policy.boardAttempts());

        assertNoBoardingAction(policy, 21, 29);
        assertEquals(NONE, boardingTick(policy).action());
        assertEquals(FALLBACK, policy.phase());
        assertEquals(3, policy.boardAttempts());
    }

    @Test
    void validSelectionRequestWithoutAnEventFallsBackAtTickForty() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(SELECT_CARRIED_BOAT, policy.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());

        for (int tick = 1;
                tick < BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS;
                tick++) {
            assertEquals(SELECT_CARRIED_BOAT, policy.tick(observation(
                    true, true, false, false, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
            assertEquals(REQUESTING_SELECTION, policy.phase());
        }
        assertEquals(NONE, policy.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void validPlacementRequestWithoutAnEventFallsBackAtTickForty() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(PLACE_HELD_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());

        for (int tick = 1;
                tick < BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS;
                tick++) {
            assertEquals(PLACE_HELD_BOAT, policy.tick(observation(
                    true, true, false, true, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
            assertEquals(REQUESTING_PLACEMENT, policy.phase());
        }
        assertEquals(NONE, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void validBoardingRequestWithoutAnEventFallsBackAtTickForty() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(BOARD_SELECTED_BOAT, policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false)).action());

        for (int tick = 1;
                tick < BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS;
                tick++) {
            assertEquals(BOARD_SELECTED_BOAT, policy.tick(observation(
                    true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), true)).action());
            assertEquals(REQUESTING_BOARDING, policy.phase());
        }
        assertEquals(NONE, policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.empty(), OptionalInt.empty(), true)).action());
        assertEquals(FALLBACK, policy.phase());
    }

    @Test
    void transactionUnavailableTicksFreezeEveryActiveTimer() {
        BoatAcquisitionPolicy selection = new BoatAcquisitionPolicy();
        assertEquals(SELECT_CARRIED_BOAT, selection.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        for (int tick = 0; tick < 100; tick++) {
            assertEquals(NONE, selection.tick(observation(
                    true, false, false, false, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
        }
        assertEquals(REQUESTING_SELECTION, selection.phase());
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.REQUEST_VALIDITY_TIMEOUT_TICKS;
                tick++) {
            assertEquals(SELECT_CARRIED_BOAT, selection.tick(observation(
                    true, true, false, false, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
        }
        assertEquals(NONE, selection.tick(observation(
                true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        assertEquals(FALLBACK, selection.phase());

        BoatAcquisitionPolicy heldItem = new BoatAcquisitionPolicy();
        heldItem.tick(observation(true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false));
        heldItem.selectionRequested();
        for (int tick = 0; tick < 100; tick++) {
            heldItem.tick(observation(true, false, false, false, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(AWAITING_HELD_ITEM, heldItem.phase());
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.HELD_ITEM_CONFIRM_TIMEOUT_TICKS;
                tick++) {
            heldItem.tick(observation(true, true, false, false, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(AWAITING_HELD_ITEM, heldItem.phase());

        BoatAcquisitionPolicy backoff = new BoatAcquisitionPolicy();
        backoff.tick(observation(true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false));
        backoff.placementResult(false);
        for (int tick = 0; tick < 100; tick++) {
            backoff.tick(observation(true, false, false, true, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(PLACEMENT_BACKOFF, backoff.phase());
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.PLACEMENT_RETRY_BACKOFF_TICKS;
                tick++) {
            assertEquals(NONE, backoff.tick(observation(
                    true, true, false, true, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
        }
        assertEquals(PLACE_HELD_BOAT, backoff.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());

        BoatAcquisitionPolicy entity = beginAcceptedPlacement();
        for (int tick = 0; tick < 100; tick++) {
            entity.tick(observation(true, false, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(AWAITING_ENTITY, entity.phase());
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.ENTITY_CONFIRM_TIMEOUT_TICKS;
                tick++) {
            entity.tick(observation(true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(AWAITING_ENTITY, entity.phase());

        BoatAcquisitionPolicy passenger = new BoatAcquisitionPolicy();
        passenger.tick(observation(true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false));
        passenger.boardingResult(41, true);
        for (int tick = 0; tick < 100; tick++) {
            passenger.tick(observation(true, false, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), true));
        }
        assertEquals(AWAITING_PASSENGER, passenger.phase());
        assertEquals(1, passenger.boardAttempts());
        assertNoBoardingAction(passenger, 1, 9);
        assertEquals(BOARD_SELECTED_BOAT, boardingTick(passenger).action());
    }

    @Test
    void fallbackStaysSuppressedUntilWaterRunReset() {
        BoatAcquisitionPolicy policy = beginAcceptedPlacement();
        for (int tick = 0; tick < BoatAcquisitionPolicy.ENTITY_CONFIRM_TIMEOUT_TICKS; tick++) {
            policy.tick(observation(true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }
        assertEquals(FALLBACK, policy.phase());

        assertEquals(NONE, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false)).action());
        policy.resetForWaterRun();
        assertEquals(BOARD_SELECTED_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.of(41), OptionalInt.empty(), false)).action());
    }

    @Test
    void resetClearsSelectedIdAttemptsRejectedPlacementsAndTimers() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision board = policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false));
        policy.boardingResult(board.boatEntityId().orElseThrow(), true);
        assertNoBoardingAction(policy, 1, 9);
        assertEquals(1, policy.boardAttempts());
        assertEquals(41, policy.selectedBoatId().orElseThrow());

        policy.resetForWaterRun();

        assertEquals(IDLE, policy.phase());
        assertEquals(0, policy.boardAttempts());
        assertTrue(policy.selectedBoatId().isEmpty());

        assertEquals(PLACE_HELD_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        policy.placementResult(false);
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.PLACEMENT_RETRY_BACKOFF_TICKS;
                tick++) {
            policy.tick(observation(true, true, false, true, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false));
        }

        policy.resetForWaterRun();

        assertEquals(PLACE_HELD_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        policy.placementResult(false);
        assertEquals(PLACEMENT_BACKOFF, policy.phase());
        for (int tick = 1;
                tick < BoatAcquisitionPolicy.PLACEMENT_RETRY_BACKOFF_TICKS;
                tick++) {
            assertEquals(NONE, policy.tick(observation(
                    true, true, false, true, true, true,
                    OptionalInt.empty(), OptionalInt.empty(), false)).action());
        }
        assertEquals(PLACE_HELD_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        assertEquals(REQUESTING_PLACEMENT, policy.phase());
    }

    @Test
    void ineligibleWaterRunResetsActiveAcquisitionEvenWhenTransactionsAreUnavailable() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        BoatAcquisitionPolicy.Decision board = policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false));
        policy.boardingResult(board.boatEntityId().orElseThrow(), true);

        assertEquals(NONE, policy.tick(observation(
                false, false, false, false, false, false,
                OptionalInt.empty(), OptionalInt.empty(), true)).action());
        assertEquals(IDLE, policy.phase());
        assertEquals(0, policy.boardAttempts());
        assertTrue(policy.selectedBoatId().isEmpty());

        assertEquals(BOARD_SELECTED_BOAT, policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.of(42), OptionalInt.empty(), false)).action());
    }

    @Test
    void recordsRejectNullsNegativeIdsAndInvalidDecisionShapes() {
        assertThrows(NullPointerException.class,
                () -> new BoatAcquisitionPolicy.Decision(null, OptionalInt.empty()));
        assertThrows(NullPointerException.class,
                () -> new BoatAcquisitionPolicy.Decision(NONE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BoatAcquisitionPolicy.Decision(NONE, OptionalInt.of(41)));
        assertThrows(IllegalArgumentException.class,
                () -> new BoatAcquisitionPolicy.Decision(BOARD_SELECTED_BOAT, OptionalInt.empty()));

        assertThrows(NullPointerException.class, () -> new BoatAcquisitionPolicy.Observation(
                true, true, false, false, false, false, false,
                null, OptionalInt.empty(), false));
        assertThrows(NullPointerException.class, () -> new BoatAcquisitionPolicy.Observation(
                true, true, false, false, false, false, false,
                OptionalInt.empty(), null, false));
        assertThrows(IllegalArgumentException.class, () -> new BoatAcquisitionPolicy.Observation(
                true, true, false, false, false, false, true,
                OptionalInt.of(-1), OptionalInt.empty(), false));
        assertThrows(IllegalArgumentException.class, () -> new BoatAcquisitionPolicy.Observation(
                true, true, false, false, false, false, false,
                OptionalInt.empty(), OptionalInt.of(-1), false));
    }

    @Test
    void invalidEventsAndWrongBoardEntityAreRejectedWithoutMutation() {
        BoatAcquisitionPolicy idle = new BoatAcquisitionPolicy();
        assertThrows(NullPointerException.class, () -> idle.tick(null));
        assertThrows(IllegalStateException.class, idle::selectionRequested);
        assertThrows(IllegalStateException.class, () -> idle.placementResult(true));
        assertThrows(IllegalArgumentException.class, () -> idle.boardingResult(-1, true));
        assertThrows(IllegalStateException.class, () -> idle.boardingResult(41, true));

        BoatAcquisitionPolicy selection = new BoatAcquisitionPolicy();
        selection.tick(observation(true, true, false, false, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false));
        selection.selectionRequested();
        assertThrows(IllegalStateException.class, selection::selectionRequested);
        assertEquals(AWAITING_HELD_ITEM, selection.phase());

        BoatAcquisitionPolicy placement = new BoatAcquisitionPolicy();
        placement.tick(observation(true, true, false, true, false, true,
                OptionalInt.empty(), OptionalInt.empty(), false));
        placement.placementResult(true);
        assertThrows(IllegalStateException.class, () -> placement.placementResult(true));
        assertEquals(AWAITING_ENTITY, placement.phase());

        BoatAcquisitionPolicy boarding = new BoatAcquisitionPolicy();
        boarding.tick(observation(true, true, false, false, false, false,
                OptionalInt.of(41), OptionalInt.empty(), false));
        assertThrows(IllegalArgumentException.class, () -> boarding.boardingResult(42, true));
        assertEquals(REQUESTING_BOARDING, boarding.phase());
        assertEquals(41, boarding.selectedBoatId().orElseThrow());
        assertEquals(0, boarding.boardAttempts());
        boarding.boardingResult(41, true);
        assertThrows(IllegalStateException.class, () -> boarding.boardingResult(41, true));
        assertEquals(AWAITING_PASSENGER, boarding.phase());
        assertEquals(1, boarding.boardAttempts());
    }

    private static BoatAcquisitionPolicy beginAcceptedPlacement() {
        BoatAcquisitionPolicy policy = new BoatAcquisitionPolicy();
        assertEquals(PLACE_HELD_BOAT, policy.tick(observation(
                true, true, false, true, true, true,
                OptionalInt.empty(), OptionalInt.empty(), false)).action());
        policy.placementResult(true);
        assertEquals(AWAITING_ENTITY, policy.phase());
        return policy;
    }

    private static BoatAcquisitionPolicy.Decision advanceToBoardRetry(
            BoatAcquisitionPolicy policy,
            int entityId
    ) {
        BoatAcquisitionPolicy.Decision decision = new BoatAcquisitionPolicy.Decision(
                NONE, OptionalInt.empty());
        for (int tick = 1;
                tick <= BoatAcquisitionPolicy.BOARD_RETRY_INTERVAL_TICKS;
                tick++) {
            decision = policy.tick(observation(
                    true, true, false, false, false, false,
                    OptionalInt.empty(), OptionalInt.empty(), true));
            if (tick < BoatAcquisitionPolicy.BOARD_RETRY_INTERVAL_TICKS) {
                assertEquals(NONE, decision.action(), "boarding tick " + tick);
            }
        }
        assertEquals(BOARD_SELECTED_BOAT, decision.action());
        assertEquals(entityId, decision.boatEntityId().orElseThrow());
        return decision;
    }

    private static void assertNoBoardingAction(
            BoatAcquisitionPolicy policy,
            int firstTick,
            int lastTick
    ) {
        for (int tick = firstTick; tick <= lastTick; tick++) {
            assertEquals(NONE, boardingTick(policy).action(), "boarding tick " + tick);
            assertEquals(AWAITING_PASSENGER, policy.phase());
        }
    }

    private static BoatAcquisitionPolicy.Decision boardingTick(BoatAcquisitionPolicy policy) {
        return policy.tick(observation(
                true, true, false, false, false, false,
                OptionalInt.empty(), OptionalInt.empty(), true));
    }

    private static BoatAcquisitionPolicy.Observation observation(
            boolean eligible,
            boolean transactionAvailable,
            boolean passengerInBoat,
            boolean heldBoatConfirmed,
            boolean carriedBoatAvailable,
            boolean placementSurfaceAvailable,
            OptionalInt nearbyBoatEntityId,
            OptionalInt newlySpawnedBoatEntityId,
            boolean selectedBoatPresent
    ) {
        return new BoatAcquisitionPolicy.Observation(
                eligible,
                transactionAvailable,
                passengerInBoat,
                heldBoatConfirmed,
                carriedBoatAvailable,
                placementSurfaceAvailable,
                nearbyBoatEntityId.isPresent(),
                nearbyBoatEntityId,
                newlySpawnedBoatEntityId,
                selectedBoatPresent
        );
    }
}
