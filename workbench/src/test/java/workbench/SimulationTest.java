package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Validation, defaults and failure semantics; no database needed. */
class SimulationTest {

    @Test
    void defaults() {
        assertEquals(new Simulation(2, 1, 0), new Simulation(2, null, null));
        assertEquals(new Simulation(null, null, 0), new Simulation(null, null, null));
    }

    @Test
    void delayWithoutFailureIsValid() {
        Simulation delayOnly = new Simulation(null, null, 300);
        assertFalse(delayOnly.failsOn(1, 1));
        assertEquals(300, delayOnly.stepDelayMs());
    }

    @Test
    void rejectsInvalidSimulations() {
        assertThrows(IllegalArgumentException.class, () -> new Simulation(0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new Simulation(6, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new Simulation(3, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Simulation(null, 2, null)); // failTimes needs failStep
        assertThrows(IllegalArgumentException.class, () -> new Simulation(null, null, -1));
        assertThrows(IllegalArgumentException.class, () -> new Simulation(null, null, Simulation.MAX_STEP_DELAY_MS + 1));
    }

    @Test
    void failsOnlyTheGivenStepForTheFirstFailTimesAttempts() {
        Simulation simulation = Simulation.failing(3, 2);
        assertTrue(simulation.failsOn(3, 1));
        assertTrue(simulation.failsOn(3, 2));
        assertFalse(simulation.failsOn(3, 3));
        assertFalse(simulation.failsOn(2, 1));
    }
}
