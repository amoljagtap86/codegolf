package workbench;

/**
 * How an execution's simulated steps behave. Every field is optional:
 * <ul>
 *   <li>{@code failStep}: the step (1-based index) that fails on its first {@code failTimes} attempts</li>
 *   <li>{@code failTimes}: defaults to 1 when {@code failStep} is set</li>
 *   <li>{@code stepDelayMs}: how long every step attempt takes, 0–5000 ms, default 0; makes
 *       parallel steps visibly overlap</li>
 * </ul>
 * Validated on construction, so an invalid simulation never exists (and Jersey answers 400 for bad
 * JSON input). Stored in executions.failure_plan (named before delays existed).
 */
public record Simulation(Integer failStep, Integer failTimes, Integer stepDelayMs) {

    public static final int MAX_STEP_DELAY_MS = 5000;

    public Simulation {
        if (failStep != null) {
            if (failStep < 1 || failStep > ProcessDefinition.ORDER.size()) {
                throw new IllegalArgumentException("failStep must be between 1 and " + ProcessDefinition.ORDER.size());
            }
            if (failTimes == null) {
                failTimes = 1;
            }
            if (failTimes < 1) {
                throw new IllegalArgumentException("failTimes must be at least 1");
            }
        } else if (failTimes != null) {
            throw new IllegalArgumentException("failTimes requires failStep");
        }
        if (stepDelayMs == null) {
            stepDelayMs = 0;
        }
        if (stepDelayMs < 0 || stepDelayMs > MAX_STEP_DELAY_MS) {
            throw new IllegalArgumentException("stepDelayMs must be between 0 and " + MAX_STEP_DELAY_MS);
        }
    }

    /** Step failStep fails on its first failTimes attempts. */
    public static Simulation failing(int failStep, int failTimes) {
        return new Simulation(failStep, failTimes, null);
    }

    public boolean failsOn(int stepIndex, int attemptNo) {
        return failStep != null && stepIndex == failStep && attemptNo <= failTimes;
    }
}
