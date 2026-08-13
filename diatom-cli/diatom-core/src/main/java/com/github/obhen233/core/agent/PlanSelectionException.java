package com.github.obhen233.core.agent;

public class PlanSelectionException extends RuntimeException {
    private final String planA;
    private final String planB;

    public PlanSelectionException(String planA, String planB) {
        super("需要选择方案: Plan A 或 Plan B");
        this.planA = planA;
        this.planB = planB;
    }

    public String getPlanA() { return planA; }
    public String getPlanB() { return planB; }
}