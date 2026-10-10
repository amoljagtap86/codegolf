package workbench;

/**
 * Simulated failure: step {@code failStep} fails on its first {@code failTimes} attempts, then succeeds.
 * Validated on construction, so an invalid plan never exists (and Jersey answers 400 for bad JSON input).
 */
public record FailurePlan(int failStep, Integer failTimes) {

    public FailurePlan {
        if (failStep < 1 || failStep > ProcessDefinition.ORDER.size()) {
            throw new IllegalArgumentException("failStep must be between 1 and " + ProcessDefinition.ORDER.size());
        }
        if (failTimes == null) {
            failTimes = 1;
        }
        if (failTimes < 1) {
            throw new IllegalArgumentException("failTimes must be at least 1");
        }
    }

    public boolean failsOn(int stepIndex, int attemptNo) {
        return stepIndex == failStep && attemptNo <= failTimes;
    }
}
