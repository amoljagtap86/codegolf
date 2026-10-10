package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import workbench.ProcessDefinition.StepDef;

/** Graph validation and stages; no database needed. */
class ProcessDefinitionTest {

    @Test
    void orderProcessIsTheExpectedDiamond() {
        assertEquals(List.of(
                new StepDef(1, "VALIDATE_ORDER", List.of(), 1),
                new StepDef(2, "RESERVE_INVENTORY", List.of("VALIDATE_ORDER"), 2),
                new StepDef(3, "CHARGE_PAYMENT", List.of("VALIDATE_ORDER"), 2),
                new StepDef(4, "CREATE_SHIPMENT", List.of("RESERVE_INVENTORY", "CHARGE_PAYMENT"), 3),
                new StepDef(5, "SEND_CONFIRMATION", List.of("CREATE_SHIPMENT"), 4)),
                ProcessDefinition.ORDER.steps());
    }

    @Test
    void stageIsOnePlusDeepestDependency() {
        ProcessDefinition process = new ProcessDefinition(List.of(
                List.of("A"), List.of("B", "A"), List.of("C"), List.of("D", "B", "C")));
        assertEquals(List.of(1, 2, 1, 3), process.steps().stream().map(StepDef::stage).toList());
    }

    @Test
    void rejectsUnknownDependency() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new ProcessDefinition(List.of(List.of("A"), List.of("B", "NOPE"))));
        assertEquals("Step B depends on NOPE, which is not defined before it", e.getMessage());
    }

    @Test
    void rejectsCycle() {
        // A -> B -> A: whichever comes first depends on one defined after it.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new ProcessDefinition(List.of(List.of("A", "B"), List.of("B", "A"))));
        assertEquals("Step A depends on B, which is not defined before it", e.getMessage());
    }

    @Test
    void rejectsSelfDependency() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessDefinition(List.of(List.of("A", "A"))));
    }

    @Test
    void rejectsDuplicateName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new ProcessDefinition(List.of(List.of("A"), List.of("A"))));
        assertEquals("Duplicate step A", e.getMessage());
    }
}
