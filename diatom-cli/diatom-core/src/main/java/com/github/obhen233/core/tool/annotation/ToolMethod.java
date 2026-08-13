package com.github.obhen233.core.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolMethod {
    String name() default "";
    String description() default "";
    String parametersSchema() default "{}";

    // Security metadata
    boolean readOnly() default false;
    boolean checkWorkspaceBoundary() default false;
    boolean requiresConfirmation() default false;
    String riskLevel() default "none";  // none, low, medium, high, critical
    String confirmationTemplate() default "";
    String riskDescriptionTemplate() default "";
}
