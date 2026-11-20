package com.example.experiment.domain;

import java.time.Instant;
import java.util.List;

/**
 * An immutable entry in an experiment's history: who changed what, when, and the full resulting snapshot.
 *
 * @param action the lifecycle action behind a state change (for example PAUSE), null otherwise
 * @param reason optional free text supplied with a state change
 */
public record ExperimentVersion(
        String experimentId,
        long version,
        long configVersion,
        ChangeType changeType,
        LifecycleAction action,
        String changedBy,
        Instant changedAt,
        String reason,
        ExperimentState state,
        List<FieldChange> changes,
        ExperimentConfig config) {

    public ExperimentVersion {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
