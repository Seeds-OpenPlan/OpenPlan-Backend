package com.openplan.backend.task.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.openplan.backend.task.repository.UnassignedTaskRow;

public record UnassignedTaskResponse(
        @JsonUnwrapped TaskResponse task,
        String projectName) {

    public static UnassignedTaskResponse from(UnassignedTaskRow row) {
        return new UnassignedTaskResponse(TaskResponse.from(row.task()), row.projectName());
    }
}
