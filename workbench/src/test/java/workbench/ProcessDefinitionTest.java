package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import workbench.ProcessDefinition.StepDef;

/** Graph validation and stages; no database needed. */
class ProcessDefinitionTest {

    @Test
    void icMonthlyRunIsTheExpectedGraph() {
        List<String> crediting = List.of("LOAD_SALES_DATA", "LOAD_ALIGNMENT", "LOAD_ROSTER");
        assertEquals(List.of(
                new StepDef(1, "LOAD_SALES_DATA", List.of(), 1),
                new StepDef(2, "LOAD_ALIGNMENT", List.of(), 1),
                new StepDef(3, "LOAD_ROSTER", List.of(), 1),
                new StepDef(4, "LOAD_GOALS", List.of(), 1),
                new StepDef(5, "CREDIT_SALES", crediting, 2),
                new StepDef(6, "COMPUTE_ATTAINMENT", List.of("CREDIT_SALES", "LOAD_GOALS"), 3),
                new StepDef(7, "CALCULATE_PAYOUTS", List.of("COMPUTE_ATTAINMENT"), 4),
                new StepDef(8, "REVIEW_PAYOUTS", List.of("CALCULATE_PAYOUTS"), 5),
                new StepDef(9, "PUBLISH_STATEMENTS", List.of("REVIEW_PAYOUTS"), 6)),
                ProcessDefinition.IC_MONTHLY_RUN.steps());
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
