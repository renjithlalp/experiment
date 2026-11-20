package com.example.experiment.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The only moves allowed between lifecycle states. */
public enum LifecycleAction {
    PUBLISH(ExperimentState.SCHEDULED, false, ExperimentState.DRAFT),
    UNPUBLISH(ExperimentState.DRAFT, false, ExperimentState.SCHEDULED),
    /** Performed by the scheduler when the start time is reached. */
    START(ExperimentState.RUNNING, true, ExperimentState.SCHEDULED),
    PAUSE(ExperimentState.PAUSED, false, ExperimentState.RUNNING),
    RESUME(ExperimentState.RUNNING, false, ExperimentState.PAUSED),
    END(ExperimentState.ENDED, false, ExperimentState.SCHEDULED, ExperimentState.RUNNING, ExperimentState.PAUSED);

    private final ExperimentState target;
    private final boolean systemOnly;
    private final Set<ExperimentState> from;

    LifecycleAction(ExperimentState target, boolean systemOnly, ExperimentState first, ExperimentState... rest) {
        this.target = target;
        this.systemOnly = systemOnly;
        this.from = EnumSet.of(first, rest);
    }

    public ExperimentState target() {
        return target;
    }

    public boolean systemOnly() {
        return systemOnly;
    }

    public boolean allowedFrom(ExperimentState state) {
        return from.contains(state);
    }

    /** Actions a user may take from the given state, for error messages and clients. */
    public static List<LifecycleAction> userActionsFrom(ExperimentState state) {
        return Arrays.stream(values()).filter(a -> !a.systemOnly && a.allowedFrom(state)).toList();
    }

    /** Case-insensitive lookup; null when the name is not an action. */
    public static LifecycleAction parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
