package com.example.experiment.service;

import java.util.List;

/** A rejected request. Carries the HTTP status, a stable code and, for validation, every failing field. */
public class ExperimentException extends RuntimeException {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String EXPERIMENT_LOCKED = "EXPERIMENT_LOCKED";
    public static final String READ_ONLY = "READ_ONLY";
    public static final String INVALID_TRANSITION = "INVALID_TRANSITION";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";

    private final int status;
    private final String code;
    private final List<FieldError> errors;

    public ExperimentException(int status, String code, String message, List<FieldError> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ExperimentException validation(List<FieldError> errors) {
        String summary = errors.size() == 1 ? errors.get(0).message() : errors.size() + " fields are invalid";
        return new ExperimentException(400, VALIDATION_FAILED, summary, errors);
    }

    public static ExperimentException badRequest(String field, String message) {
        return validation(List.of(new FieldError(field, message)));
    }

    public static ExperimentException notFound(String what) {
        return new ExperimentException(404, NOT_FOUND, what + " not found", null);
    }

    public static ExperimentException conflict(String code, String message) {
        return new ExperimentException(409, code, message, null);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
