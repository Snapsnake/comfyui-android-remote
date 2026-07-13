package com.snapsnake.comfyremote.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WorkflowPreflightException extends IllegalStateException {
    public final List<WorkflowPreflight.Issue> issues;

    public WorkflowPreflightException(List<WorkflowPreflight.Issue> issues) {
        super(message(issues));
        this.issues = Collections.unmodifiableList(new ArrayList<>(
                issues == null ? Collections.emptyList() : issues
        ));
    }

    private static String message(List<WorkflowPreflight.Issue> issues) {
        if (issues == null || issues.isEmpty()) return "Workflow preflight failed";
        StringBuilder out = new StringBuilder("Workflow has ")
                .append(issues.size()).append(" invalid field(s): ");
        int max = Math.min(3, issues.size());
        for (int i = 0; i < max; i++) {
            if (i > 0) out.append("; ");
            out.append(issues.get(i).summary());
        }
        if (issues.size() > max) out.append("; …");
        return out.toString();
    }
}
