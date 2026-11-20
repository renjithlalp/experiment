package com.example.experiment.domain;

import java.time.Instant;

/**
 * Everything a user configures on an experiment. Experiment details (name, dates, type, priority) come first;
 * the split, bucketing identifier and treatment may be added later while the experiment is a Draft.
 */
public record ExperimentConfig(
        String name,
        String description,
        Instant startAt,
        Instant endAt,
        TreatmentType treatmentType,
        Integer priority,
        Split split,
        Bucketing bucketing,
        PricingTreatment pricing) {
}
