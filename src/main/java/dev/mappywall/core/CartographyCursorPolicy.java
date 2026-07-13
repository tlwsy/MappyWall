package dev.mappywall.core;

/** Orders cartography cursor recovery ahead of declaring a zoom workflow complete. */
public final class CartographyCursorPolicy {
    public Action nextAction(
            boolean cursorEmpty,
            boolean carriedTaskMap,
            boolean hasEmptyInventorySlot,
            boolean targetScaleReached
    ) {
        if (!cursorEmpty) {
            if (!carriedTaskMap) {
                return Action.CLEAR_CURSOR_MANUALLY;
            }
            return hasEmptyInventorySlot ? Action.STORE_TASK_MAP : Action.NEED_SPACE;
        }
        return targetScaleReached ? Action.ZOOM_COMPLETE : Action.CONTINUE_ZOOM;
    }

    public enum Action {
        STORE_TASK_MAP,
        CLEAR_CURSOR_MANUALLY,
        NEED_SPACE,
        ZOOM_COMPLETE,
        CONTINUE_ZOOM
    }
}
