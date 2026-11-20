package com.example.experiment.service;

/** One reason a request was rejected, tied to the field (dotted path) that caused it. */
public record FieldError(String field, String message) {
}
