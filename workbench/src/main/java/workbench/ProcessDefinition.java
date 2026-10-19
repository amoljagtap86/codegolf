package workbench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A process as a DAG of steps. Validated on construction: names are unique and every dependency
 * names a step defined <em>earlier</em> in the list. That one rule rejects unknown dependencies
 * and cycles, and makes definition order a valid topological order.
 */
public final class ProcessDefinition {

    /**
     * @param index 1-based position in the definition; the stable id stored in step_attempts.step_index
     * @param stage dependency depth: 1 for steps without dependencies, else 1 + the deepest dependency
     */
    public record StepDef(int index, String name, List<String> dependsOn, int stage) {
    }

    /**
     * A simulated monthly incentive compensation run. The four input feeds load in parallel;
     * crediting needs sales, alignment and roster; attainment also needs goals. REVIEW_PAYOUTS is
     * where payouts are checked before statements go out (a pass-through until milestone 7).
     */
    public static final ProcessDefinition IC_MONTHLY_RUN = new ProcessDefinition(List.of(
            List.of("LOAD_SALES_DATA"),
            List.of("LOAD_ALIGNMENT"),
            List.of("LOAD_ROSTER"),
            List.of("LOAD_GOALS"),
            List.of("CREDIT_SALES", "LOAD_SALES_DATA", "LOAD_ALIGNMENT", "LOAD_ROSTER"),
            List.of("COMPUTE_ATTAINMENT", "CREDIT_SALES", "LOAD_GOALS"),
            List.of("CALCULATE_PAYOUTS", "COMPUTE_ATTAINMENT"),
            List.of("REVIEW_PAYOUTS", "CALCULATE_PAYOUTS"),
            List.of("PUBLISH_STATEMENTS", "REVIEW_PAYOUTS")));

    private final List<StepDef> steps = new ArrayList<>();

    /** Each entry is [name, dependency...]. */
    ProcessDefinition(List<List<String>> definition) {
        Map<String, StepDef> byName = new HashMap<>();
        for (List<String> entry : definition) {
            String name = entry.get(0);
            List<String> dependsOn = List.copyOf(entry.subList(1, entry.size()));
            if (byName.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate step " + name);
            }
            int stage = 1;
            for (String dependency : dependsOn) {
                StepDef earlier = byName.get(dependency);
                if (earlier == null) {
                    throw new IllegalArgumentException(
                            "Step " + name + " depends on " + dependency + ", which is not defined before it");
                }
                stage = Math.max(stage, earlier.stage() + 1);
            }
            StepDef step = new StepDef(steps.size() + 1, name, dependsOn, stage);
            steps.add(step);
            byName.put(name, step);
        }
    }

    public List<StepDef> steps() {
        return List.copyOf(steps);
    }

    public StepDef step(int index) {
        return steps.get(index - 1);
    }

    public int size() {
        return steps.size();
    }
}
