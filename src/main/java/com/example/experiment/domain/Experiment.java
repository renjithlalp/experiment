package com.example.experiment.domain;

import java.time.Instant;

/**
 * Current state of an experiment.
 *
 * @param version       increases on every change, to the configuration or the state
 * @param configVersion increases only when the configuration changes; traffic is attributed to this
 */
public record Experiment(
        String id,
        long version,
        long configVersion,
        ExperimentState state,
        ExperimentConfig config,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    public Experiment withConfig(ExperimentConfig newConfig, String user, Instant at) {
        return new Experiment(id, version + 1, configVersion + 1, state, newConfig, createdBy, createdAt, user, at);
    }

    public Experiment withState(ExperimentState newState, String user, Instant at) {
        return new Experiment(id, version + 1, configVersion, newState, config, createdBy, createdAt, user, at);
    }
}
