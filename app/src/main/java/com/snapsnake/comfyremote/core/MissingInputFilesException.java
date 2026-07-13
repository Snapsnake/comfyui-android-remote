package com.snapsnake.comfyremote.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MissingInputFilesException extends IllegalStateException {
    public final List<WorkflowFileRequirement> files;

    public MissingInputFilesException(List<WorkflowFileRequirement> files) {
        super(message(files));
        this.files = Collections.unmodifiableList(new ArrayList<>(
                files == null ? Collections.emptyList() : files
        ));
    }

    private static String message(List<WorkflowFileRequirement> files) {
        if (files == null || files.isEmpty()) return "Required ComfyUI input files are missing";
        StringBuilder out = new StringBuilder("Missing ")
                .append(files.size()).append(" ComfyUI input file(s): ");
        int max = Math.min(3, files.size());
        for (int i = 0; i < max; i++) {
            WorkflowFileRequirement file = files.get(i);
            if (i > 0) out.append("; ");
            out.append(file.value.isEmpty() ? file.nodeTitle + " · " + file.key : file.value);
        }
        if (files.size() > max) out.append("; …");
        return out.toString();
    }
}
