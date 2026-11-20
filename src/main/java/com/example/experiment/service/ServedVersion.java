package com.example.experiment.service;

import com.example.experiment.domain.ExperimentConfig;
import com.example.experiment.domain.ExperimentState;
import java.time.Instant;

/**
 * What an experiment served at a given instant.
 *
 * @param state              the state at that instant, null when the experiment did not exist yet
 * @param defaultExperience  true when customers got the default (experiment not Running)
 * @param configVersion      the configuration version customers were bucketed against, when Running
 * @param version            the history entry that was current at that instant
 * @param config             the configuration that was live, when Running
 */
public record ServedVersion(
        String experimentId,
        Instant at,
        ExperimentState state,
        boolean defaultExperience,
        Long configVersion,
        Long version,
        ExperimentConfig config) {
}
