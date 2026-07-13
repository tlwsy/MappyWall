package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CartographyCursorPolicyTest {
    private final CartographyCursorPolicy policy = new CartographyCursorPolicy();

    @Test
    void storesTargetScaleTaskMapBeforeDeclaringZoomComplete() {
        assertEquals(
                CartographyCursorPolicy.Action.STORE_TASK_MAP,
                policy.nextAction(false, true, true, true)
        );
    }

    @Test
    void declaresZoomCompleteOnlyAfterCursorIsEmpty() {
        assertEquals(
                CartographyCursorPolicy.Action.ZOOM_COMPLETE,
                policy.nextAction(true, false, false, true)
        );
    }

    @Test
    void doesNotMoveAnUnrelatedCursorItem() {
        assertEquals(
                CartographyCursorPolicy.Action.CLEAR_CURSOR_MANUALLY,
                policy.nextAction(false, false, true, false)
        );
    }

    @Test
    void requiresSpaceBeforeStoringTheTaskMap() {
        assertEquals(
                CartographyCursorPolicy.Action.NEED_SPACE,
                policy.nextAction(false, true, false, true)
        );
    }
}
