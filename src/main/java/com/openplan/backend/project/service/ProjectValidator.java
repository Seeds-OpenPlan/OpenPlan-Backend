package com.openplan.backend.project.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
public class ProjectValidator {

    private static final int NAME_MAX = 100;

    private final ErrorMessages errorMessages;

    public ProjectValidator(ErrorMessages errorMessages) {
        this.errorMessages = errorMessages;
    }

    public String validateName(String name) {
        String trimmed = (name == null) ? "" : name.trim(); // ← C2 null 가드(첫 줄)
        if (trimmed.isEmpty()) {
            throw invalid("name", "required");
        }
        if (trimmed.length() > NAME_MAX) {
            throw invalid("name", "size");
        }
        return trimmed;
    }

    public void validateDueDate(LocalDate dueDate, LocalDate today) {
        if (dueDate != null && dueDate.isBefore(today)) {
            throw invalid("dueDate", "past");
        }
    }

    public void validatePriority(Integer priority) {
        if (priority == null) {
            return; // null 통과(미지정 허용)
        }
        if (priority < 1 || priority > 3) {
            throw invalid("priority", "range");
        }
    }

    private OpenPlanException invalid(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
