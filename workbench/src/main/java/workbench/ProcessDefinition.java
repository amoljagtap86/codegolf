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

    /** The simulated order process: inventory and payment both depend only on validation. */
    public static final ProcessDefinition ORDER = new ProcessDefinition(List.of(
            List.of("VALIDATE_ORDER"),
            List.of("RESERVE_INVENTORY", "VALIDATE_ORDER"),
            List.of("CHARGE_PAYMENT", "VALIDATE_ORDER"),
            List.of("CREATE_SHIPMENT", "RESERVE_INVENTORY", "CHARGE_PAYMENT"),
            List.of("SEND_CONFIRMATION", "CREATE_SHIPMENT")));

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
