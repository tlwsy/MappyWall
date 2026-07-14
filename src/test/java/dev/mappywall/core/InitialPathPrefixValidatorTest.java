package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mappywall.core.InitialPathPrefixValidator.InitialPathStep;
import dev.mappywall.core.InitialPathPrefixValidator.InitialStepKind;
import dev.mappywall.core.InitialPathPrefixValidator.PrefixDecision;
import dev.mappywall.core.InitialPathPrefixValidator.PrefixStatus;
import dev.mappywall.core.PathSegmentCoordinator.Anchor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InitialPathPrefixValidatorTest {
    private final InitialPathPrefixValidator validator = new InitialPathPrefixValidator();

    @Test
    void acceptsExactStart() {
        PrefixDecision decision = validator.validate(
                anchor(0, 64, 0),
                anchor(0, 64, 0),
                List.of(walk(1, 64, 0), walk(2, 64, 0)),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.ACCEPT, 0), decision);
    }

    @Test
    void trimsConsumedMovementPrefix() {
        PrefixDecision decision = validator.validate(
                anchor(2, 64, 0),
                anchor(0, 64, 0),
                List.of(walk(1, 64, 0), walk(2, 64, 0), walk(3, 64, 0)),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.TRIM, 2), decision);
    }

    @Test
    void trimsAtMostThreeMovementSteps() {
        PrefixDecision decision = validator.validate(
                anchor(4, 64, 0),
                anchor(0, 64, 0),
                List.of(
                        walk(1, 64, 0),
                        walk(2, 64, 0),
                        walk(3, 64, 0),
                        walk(4, 64, 0),
                        walk(5, 64, 0)
                ),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.REJECT, 0), decision);
    }

    @Test
    void rejectsTwoColumnFirstTransition() {
        PrefixDecision decision = validator.validate(
                anchor(0, 64, 0),
                anchor(0, 64, 0),
                List.of(walk(2, 64, 0)),
                3
        );

        assertEquals(PrefixStatus.REJECT, decision.status());
    }

    @Test
    void rejectsUnsafeDiagonalStructure() {
        PrefixDecision decision = validator.validate(
                anchor(0, 64, 0),
                anchor(0, 64, 0),
                List.of(step(1, 65, 1, InitialStepKind.JUMP)),
                3
        );

        assertEquals(PrefixStatus.REJECT, decision.status());
    }

    @Test
    void allowsDiagonalWalkForLaterLiveSideValidation() {
        PrefixDecision decision = validator.validate(
                anchor(0, 64, 0),
                anchor(0, 64, 0),
                List.of(walk(1, 64, 1)),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.ACCEPT, 0), decision);
    }

    @Test
    void neverTrimsModificationAction() {
        PrefixDecision decision = validator.validate(
                anchor(1, 64, 0),
                anchor(0, 64, 0),
                List.of(
                        step(1, 64, 0, InitialStepKind.BREAK),
                        walk(2, 64, 0)
                ),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.REJECT, 0), decision);
    }

    @Test
    void rejectsStaleOriginModification() {
        PrefixDecision decision = validator.validate(
                anchor(0, 64, 1),
                anchor(0, 64, 0),
                List.of(step(1, 64, 0, InitialStepKind.PLACE)),
                3
        );

        assertEquals(PrefixStatus.REJECT, decision.status());
    }

    @Test
    void trimsMovementButLeavesFollowingModification() {
        PrefixDecision decision = validator.validate(
                anchor(1, 64, 0),
                anchor(0, 64, 0),
                List.of(
                        walk(1, 64, 0),
                        step(2, 64, 0, InitialStepKind.BREAK),
                        walk(3, 64, 0)
                ),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.TRIM, 1), decision);
    }

    @Test
    void rejectsPositionAtLaterModificationEndpoint() {
        PrefixDecision decision = validator.validate(
                anchor(2, 64, 0),
                anchor(0, 64, 0),
                List.of(
                        walk(1, 64, 0),
                        step(2, 64, 0, InitialStepKind.BREAK),
                        walk(3, 64, 0)
                ),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.REJECT, 0), decision);
    }

    @Test
    void acceptsJoinableDriftBeforeLaterModification() {
        PrefixDecision decision = validator.validate(
                anchor(0, 64, 1),
                anchor(0, 64, 0),
                List.of(
                        walk(1, 64, 0),
                        step(2, 64, 0, InitialStepKind.BREAK)
                ),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.ACCEPT, 0), decision);
    }

    @Test
    void rejectsMalformedModificationInsideProbedChain() {
        PrefixDecision decision = validator.validate(
                anchor(0, 64, 0),
                anchor(0, 64, 0),
                List.of(
                        walk(1, 64, 0),
                        step(3, 64, 0, InitialStepKind.PLACE)
                ),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.REJECT, 0), decision);
    }

    @Test
    void reportsAllConsumedStepsForClientToRecapture() {
        PrefixDecision decision = validator.validate(
                anchor(2, 64, 0),
                anchor(0, 64, 0),
                List.of(walk(1, 64, 0), walk(2, 64, 0)),
                3
        );

        assertEquals(new PrefixDecision(PrefixStatus.TRIM, 2), decision);
    }

    @Test
    void rejectsInvalidInputsAndNullSteps() {
        List<InitialPathStep> withNull = new ArrayList<>();
        withNull.add(walk(1, 64, 0));
        withNull.add(null);

        assertThrows(NullPointerException.class,
                () -> validator.validate(null, anchor(0, 64, 0), List.of(), 3));
        assertThrows(NullPointerException.class,
                () -> validator.validate(anchor(0, 64, 0), null, List.of(), 3));
        assertThrows(NullPointerException.class,
                () -> validator.validate(anchor(0, 64, 0), anchor(0, 64, 0), null, 3));
        assertThrows(NullPointerException.class,
                () -> validator.validate(anchor(0, 64, 0), anchor(0, 64, 0), withNull, 3));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(anchor(0, 64, 0), anchor(0, 64, 0), List.of(), 0));
        assertThrows(NullPointerException.class,
                () -> new InitialPathStep(null, InitialStepKind.WALK));
        assertThrows(NullPointerException.class,
                () -> new InitialPathStep(anchor(0, 64, 0), null));
        assertThrows(IllegalArgumentException.class,
                () -> new PrefixDecision(PrefixStatus.ACCEPT, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PrefixDecision(PrefixStatus.REJECT, 1));
    }

    private static InitialPathStep walk(int x, int y, int z) {
        return step(x, y, z, InitialStepKind.WALK);
    }

    private static InitialPathStep step(int x, int y, int z, InitialStepKind kind) {
        return new InitialPathStep(anchor(x, y, z), kind);
    }

    private static Anchor anchor(int x, int y, int z) {
        return new Anchor(x, y, z);
    }
}
