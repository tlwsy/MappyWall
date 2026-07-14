package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.mappywall.core.AutomationStyle;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MovementControllerPlanningGapTest {
    @Test
    void aggressivePlanningGapClearsOnlyHorizontalVelocity() {
        Vec3 current = new Vec3(0.42, -0.1875, -0.31);

        Vec3 stopped = MovementController.planningGapVelocity(
                AutomationStyle.AGGRESSIVE,
                current
        );

        assertEquals(0.0, stopped.x);
        assertEquals(current.y, stopped.y);
        assertEquals(0.0, stopped.z);
    }

    @Test
    void normalPlanningGapLeavesVelocityUnchanged() {
        Vec3 current = new Vec3(0.42, 0.08, -0.31);

        Vec3 unchanged = MovementController.planningGapVelocity(
                AutomationStyle.NORMAL,
                current
        );

        assertSame(current, unchanged);
    }
}
