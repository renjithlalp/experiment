package com.example.experiment.domain;

/** The five lifecycle stages of an experiment. */
public enum ExperimentState {
    DRAFT, SCHEDULED, RUNNING, PAUSED, ENDED;

    /** Running lock: once started, settings cannot change (only pause, resume, end). */
    public boolean isRunningLocked() {
        return this == RUNNING || this == PAUSED;
    }
}
