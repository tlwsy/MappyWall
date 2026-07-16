package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class NavigationFeetResolverTest {
    private final NavigationFeetResolver resolver = new NavigationFeetResolver();

    @ParameterizedTest
    @ValueSource(doubles = {64.0, 64.00005})
    void integerTopsAndValuesWithinEpsilonDoNotRaiseAnExtraCell(double physicalFeetY) {
        BlockPos resolved = resolver.resolve(
                4.25,
                physicalFeetY,
                -2.75,
                true,
                ignored -> true
        );

        assertEquals(new BlockPos(4, 64, -3), resolved);
    }

    @ParameterizedTest
    @ValueSource(doubles = {63.9375, 63.875, 63.5, 63.125})
    void groundedPartialBlockTopsResolveToTheLogicalFeetCell(double physicalFeetY) {
        BlockPos support = new BlockPos(3, 63, -5);

        BlockPos resolved = resolver.resolve(
                3.75,
                physicalFeetY,
                -4.25,
                true,
                support::equals
        );

        assertEquals(support.above(), resolved);
    }

    @ParameterizedTest
    @ValueSource(doubles = {63.9375, 63.875, 63.5, 63.125})
    void groundedSupportedWaypointYSelectsTheLogicalFeetCell(double physicalFeetY) {
        BlockPos support = new BlockPos(3, 63, -5);

        double waypointY = resolver.resolveWaypointY(
                3.75,
                physicalFeetY,
                -4.25,
                true,
                support::equals
        );

        assertEquals(64.0, waypointY);
    }

    @Test
    void waypointYRetainsPhysicalDoubleWhenSupportDoesNotNormalizeTheCell() {
        double physicalFeetY = 63.9375;

        double waypointY = resolver.resolveWaypointY(
                3.75,
                physicalFeetY,
                -4.25,
                true,
                ignored -> false
        );

        assertEquals(physicalFeetY, waypointY);
    }

    @Test
    void waypointYRetainsPhysicalDoubleWhenNormalizationIsDisabled() {
        double physicalFeetY = 63.9375;

        double waypointY = resolver.resolveWaypointY(
                3.75,
                physicalFeetY,
                -4.25,
                false,
                ignored -> true
        );

        assertEquals(physicalFeetY, waypointY);
    }

    @Test
    void waypointYRetainsExactPhysicalDoubleWhenResolvedCellIsUnchanged() {
        double physicalFeetY = 64.00005;

        double waypointY = resolver.resolveWaypointY(
                3.75,
                physicalFeetY,
                -4.25,
                true,
                ignored -> true
        );

        assertEquals(physicalFeetY, waypointY);
    }

    @ParameterizedTest(name = "{0} excludes logical-feet normalization")
    @CsvSource({
            "no_level, false, true, false, false, false, false",
            "airborne, true, false, false, false, false, false",
            "passenger, true, true, true, false, false, false",
            "water, true, true, false, true, false, false",
            "swimming, true, true, false, false, true, false",
            "climbing, true, true, false, false, false, true"
    })
    void everyLiveStateExclusionDisablesNormalization(
            String state,
            boolean hasLevel,
            boolean onGround,
            boolean passenger,
            boolean inWater,
            boolean swimming,
            boolean climbing
    ) {
        assertFalse(NavigationFeetResolver.isNormalizationEligible(
                hasLevel,
                onGround,
                passenger,
                inWater,
                swimming,
                climbing
        ), state);
    }

    @Test
    void groundedBlockStateIsEligibleForNormalization() {
        assertTrue(NavigationFeetResolver.isNormalizationEligible(
                true,
                true,
                false,
                false,
                false,
                false
        ));
    }

    @Test
    void liveProjectionDelegatesEligibilityToThePurePolicy() throws IOException {
        Path sourcePath = Path.of(
                "src",
                "client",
                "java",
                "dev",
                "mappywall",
                "client",
                "NavigationFeetResolver.java"
        );
        String source = Files.readString(sourcePath);
        int methodStart = source.indexOf("BlockPos resolveProjected(LocalPlayer player");
        int methodEnd = source.indexOf("BlockPos resolve(", methodStart);
        String methodSource = source.substring(methodStart, methodEnd);

        assertTrue(methodSource.contains("boolean groundedOnBlock = isNormalizationEligible("));
        assertFalse(methodSource.contains("&& !player.isPassenger()"));
        assertFalse(methodSource.contains("&& !player.isSwimming()"));
        assertFalse(methodSource.contains("&& !player.onClimbable()"));
    }

    @Test
    void liveWaypointSelectionReusesTheResolvedLiveFeet() throws IOException {
        Path sourcePath = Path.of(
                "src",
                "client",
                "java",
                "dev",
                "mappywall",
                "client",
                "NavigationFeetResolver.java"
        );
        String source = Files.readString(sourcePath);
        int methodStart = source.indexOf("double resolveWaypointY(LocalPlayer player)");
        int methodEnd = source.indexOf("BlockPos resolveProjected", methodStart);
        String methodSource = source.substring(methodStart, methodEnd);

        assertTrue(methodSource.contains("selectWaypointY(physicalFeetY, resolve(player))"));
    }

    @ParameterizedTest(name = "{0} keeps raw feet semantics")
    @ValueSource(strings = {"airborne", "passenger", "water", "swimming", "climbing"})
    void statesThatAreNotGroundedOnABlockRemainFloored(String state) {
        BlockPos resolved = resolver.resolve(
                3.75,
                63.9375,
                -4.25,
                false,
                ignored -> true
        );

        assertEquals(new BlockPos(3, 63, -5), resolved, state);
    }

    @Test
    void entityOrBoatSupportWithoutBlockCollisionRemainsFloored() {
        BlockPos resolved = resolver.resolve(
                7.25,
                63.5,
                9.75,
                true,
                ignored -> false
        );

        assertEquals(new BlockPos(7, 63, 9), resolved);
    }

    @Test
    void projectedCoordinatesProbeTheDestinationSupportColumn() {
        BlockPos destinationSupport = new BlockPos(12, 63, -8);
        AtomicReference<BlockPos> probedSupport = new AtomicReference<>();

        BlockPos resolved = resolver.resolve(
                12.75,
                63.9375,
                -7.25,
                true,
                support -> {
                    probedSupport.set(support);
                    return destinationSupport.equals(support);
                }
        );

        assertEquals(destinationSupport, probedSupport.get());
        assertEquals(destinationSupport.above(), resolved);
    }

    @Test
    void negativeCoordinatesUseFloorAndCeilBesideTheWorldBottom() {
        BlockPos bottomSupport = new BlockPos(-1, -64, -13);

        BlockPos resolved = resolver.resolve(
                -0.25,
                -63.5,
                -12.01,
                true,
                bottomSupport::equals
        );

        assertEquals(bottomSupport.above(), resolved);
    }
}
