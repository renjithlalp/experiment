package com.example.experiment.service;

import com.example.experiment.domain.ChangeType;
import com.example.experiment.domain.Experiment;
import com.example.experiment.domain.ExperimentConfig;
import com.example.experiment.domain.ExperimentState;
import com.example.experiment.domain.ExperimentVersion;
import com.example.experiment.domain.FieldChange;
import com.example.experiment.domain.LifecycleAction;
import com.example.experiment.store.ExperimentRepository;
import com.example.experiment.validation.ExperimentValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Creates, edits and moves experiments through their lifecycle, enforcing validation, the running lock and the
 * append-only version history. Methods that change data are synchronized so a check and its save cannot interleave
 * with another change.
 */
public final class ExperimentService {

    /** Recorded as the user for transitions made by the scheduler. */
    public static final String SYSTEM_USER = "system";

    private final ExperimentRepository repository;
    private final ExperimentValidator validator;
    private final Clock clock;

    public ExperimentService(ExperimentRepository repository, ExperimentValidator validator, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.validator = Objects.requireNonNull(validator);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---------------------------------------------------------------- configuration

    public synchronized Experiment create(ExperimentConfig config, String user) {
        requireUser(user);
        failIfInvalid(validator.checkSave(config));
        Instant now = clock.instant();
        Experiment experiment = new Experiment(repository.nextId(), 1, 1, ExperimentState.DRAFT, config,
                user, now, user, now);
        repository.save(experiment, new ExperimentVersion(experiment.id(), 1, 1, ChangeType.CREATED, null, user,
                now, null, ExperimentState.DRAFT, ConfigDiff.between(null, config), config));
        return experiment;
    }

    /**
     * Replaces the configuration of a Draft. Returns the experiment unchanged, without a new version, when the
     * configuration is identical.
     *
     * @param expectedVersion the version the client last read, or null to skip the concurrency check
     */
    public synchronized Experiment update(String id, Long expectedVersion, ExperimentConfig config, String user) {
        requireUser(user);
        Experiment current = get(id);
        checkExpectedVersion(current, expectedVersion);
        rejectIfNotEditable(current);
        failIfInvalid(validator.checkSave(config));
        List<FieldChange> changes = ConfigDiff.between(current.config(), config);
        if (changes.isEmpty()) {
            return current;
        }
        Instant now = clock.instant();
        Experiment updated = current.withConfig(config, user, now);
        repository.save(updated, new ExperimentVersion(id, updated.version(), updated.configVersion(),
                ChangeType.UPDATED, null, user, now, null, updated.state(), changes, config));
        return updated;
    }

    // ---------------------------------------------------------------- lifecycle

    /** A user-requested lifecycle action: publish, unpublish, pause, resume or end. */
    public synchronized Experiment apply(String id, LifecycleAction action, Long expectedVersion, String user,
                                         String reason) {
        requireUser(user);
        Objects.requireNonNull(action, "action");
        Experiment current = get(id);
        checkExpectedVersion(current, expectedVersion);
        if (action.systemOnly()) {
            throw ExperimentException.conflict(ExperimentException.INVALID_TRANSITION,
                    "An experiment starts automatically when its start time is reached; '" + action.label()
                            + "' cannot be requested. Allowed actions: " + allowed(current.state()));
        }
        Instant now = clock.instant();
        checkTransition(current, action);
        if (action == LifecycleAction.PUBLISH) {
            failIfInvalid(validator.checkPublish(current.config(), now));
        }
        if (action == LifecycleAction.RESUME && !current.config().endAt().isAfter(now)) {
            throw ExperimentException.conflict(ExperimentException.INVALID_TRANSITION,
                    "Cannot resume: the end time " + current.config().endAt() + " has passed");
        }
        return transition(current, action, user, now, reason);
    }

    /**
     * Applies time-based moves: Scheduled experiments whose start time is reached start, and Scheduled, Running
     * or Paused experiments whose end time is reached end. Safe to call any number of times.
     *
     * @return how many experiments changed state
     */
    public synchronized int tick() {
        Instant now = clock.instant();
        int changed = 0;
        for (Experiment e : repository.findAll()) {
            ExperimentConfig c = e.config();
            boolean ended = !c.endAt().isAfter(now);
            if (e.state() == ExperimentState.SCHEDULED && ended) {
                transition(e, LifecycleAction.END, SYSTEM_USER, now, "end time reached before start");
                changed++;
            } else if (e.state() == ExperimentState.SCHEDULED && !c.startAt().isAfter(now)) {
                transition(e, LifecycleAction.START, SYSTEM_USER, now, "start time reached");
                changed++;
            } else if (e.state().isRunningLocked() && ended) {
                transition(e, LifecycleAction.END, SYSTEM_USER, now, "end time reached");
                changed++;
            }
        }
        return changed;
    }

    private Experiment transition(Experiment current, LifecycleAction action, String user, Instant now,
                                  String reason) {
        Experiment moved = current.withState(action.target(), user, now);
        List<FieldChange> changes = List.of(new FieldChange("state", current.state().name(), moved.state().name()));
        repository.save(moved, new ExperimentVersion(moved.id(), moved.version(), moved.configVersion(),
                ChangeType.STATE_CHANGED, action, user, now, blankToNull(reason), moved.state(), changes,
                moved.config()));
        return moved;
    }

    // ---------------------------------------------------------------- queries

    public Experiment get(String id) {
        return repository.find(id).orElseThrow(() -> ExperimentException.notFound("Experiment " + id));
    }

    public List<Experiment> list(ExperimentState state) {
        return repository.findAll().stream().filter(e -> state == null || e.state() == state).toList();
    }

    public List<ExperimentVersion> versions(String id) {
        get(id);
        return repository.versions(id);
    }

    public ExperimentVersion version(String id, long number) {
        return versions(id).stream().filter(v -> v.version() == number).findFirst()
                .orElseThrow(() -> ExperimentException.notFound("Version " + number + " of experiment " + id));
    }

    /** Which configuration version (if any) customers were served at the given instant. */
    public ServedVersion servedAt(String id, Instant at) {
        Objects.requireNonNull(at, "at");
        ExperimentVersion live = null;
        for (ExperimentVersion v : versions(id)) {
            if (v.changedAt().isAfter(at)) {
                break;
            }
            live = v;
        }
        if (live == null) {
            return new ServedVersion(id, at, null, true, null, null, null);
        }
        if (live.state() != ExperimentState.RUNNING) {
            return new ServedVersion(id, at, live.state(), true, null, live.version(), null);
        }
        return new ServedVersion(id, at, live.state(), false, live.configVersion(), live.version(), live.config());
    }

    /** What the bucketing engine needs: Running experiments only, highest priority first. */
    public List<ActiveExperiment> activeExperiments() {
        return repository.findAll().stream()
                .filter(e -> e.state() == ExperimentState.RUNNING)
                .sorted(Comparator.comparing((Experiment e) -> e.config().priority()).reversed()
                        .thenComparing(Experiment::id))
                .map(ActiveExperiment::of)
                .toList();
    }

    // ---------------------------------------------------------------- rules

    private void rejectIfNotEditable(Experiment e) {
        switch (e.state()) {
            case DRAFT -> {
            }
            case SCHEDULED -> throw ExperimentException.conflict(ExperimentException.EXPERIMENT_LOCKED,
                    "Experiment " + e.id() + " is SCHEDULED (published); unpublish it to Draft before editing.");
            case RUNNING, PAUSED -> throw ExperimentException.conflict(ExperimentException.EXPERIMENT_LOCKED,
                    "Experiment " + e.id() + " is " + e.state() + "; only pause, resume or end are allowed so the "
                            + "results stay reliable. If a different setup is needed, end this experiment and "
                            + "create a new one.");
            case ENDED -> throw readOnly(e);
        }
    }

    private void checkTransition(Experiment e, LifecycleAction action) {
        if (e.state() == ExperimentState.ENDED) {
            throw readOnly(e);
        }
        if (!action.allowedFrom(e.state())) {
            throw ExperimentException.conflict(ExperimentException.INVALID_TRANSITION,
                    "Cannot " + action.label() + " an experiment that is " + e.state() + ". Allowed actions: "
                            + allowed(e.state()));
        }
    }

    private static ExperimentException readOnly(Experiment e) {
        return ExperimentException.conflict(ExperimentException.READ_ONLY,
                "Experiment " + e.id() + " has ENDED and is read-only.");
    }

    private static String allowed(ExperimentState state) {
        List<LifecycleAction> actions = LifecycleAction.userActionsFrom(state);
        return actions.isEmpty() ? "none"
                : actions.stream().map(LifecycleAction::label).collect(Collectors.joining(", "));
    }

    private static void checkExpectedVersion(Experiment e, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != e.version()) {
            throw ExperimentException.conflict(ExperimentException.VERSION_CONFLICT,
                    "Experiment " + e.id() + " is at version " + e.version() + " but the request was based on "
                            + "version " + expectedVersion + "; reload it and try again.");
        }
    }

    private static void requireUser(String user) {
        if (user == null || user.isBlank()) {
            throw ExperimentException.badRequest("X-User", "the user making the change is required");
        }
    }

    private static void failIfInvalid(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw ExperimentException.validation(new ArrayList<>(errors));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
