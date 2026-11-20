package com.example.experiment.domain;

/** One field that differs between two versions. Values are rendered as text; null when absent. */
public record FieldChange(String field, String from, String to) {
}
