package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Validation and failure semantics; no database needed. */
class FailurePlanTest {

    @Test
    void failTimesDefaultsToOne() {
        assertEquals(1, new FailurePlan(2, null).failTimes());
    }

    @Test
    void rejectsInvalidPlans() {
        assertThrows(IllegalArgumentException.class, () -> new FailurePlan(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FailurePlan(6, 1));
        assertThrows(IllegalArgumentException.class, () -> new FailurePlan(3, 0));
    }

    @Test
    void failsOnlyTheGivenStepForTheFirstFailTimesAttempts() {
        FailurePlan plan = new FailurePlan(3, 2);
        assertTrue(plan.failsOn(3, 1));
        assertTrue(plan.failsOn(3, 2));
        assertFalse(plan.failsOn(3, 3));
        assertFalse(plan.failsOn(2, 1));
    }
}
