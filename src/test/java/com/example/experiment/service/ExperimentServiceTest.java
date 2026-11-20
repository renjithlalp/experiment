package com.example.experiment.service;

import static com.example.experiment.Fixtures.END;
import static com.example.experiment.Fixtures.NOW;
import static com.example.experiment.Fixtures.START;
import static com.example.experiment.Fixtures.complete;
import static com.example.experiment.Fixtures.detailsOnly;
import static com.example.experiment.Fixtures.withPriority;
import static com.example.experiment.Fixtures.withSplit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.experiment.Fixtures.MutableClock;
import com.example.experiment.catalogue.InMemoryProductCatalogue;
import com.example.experiment.domain.ChangeType;
import com.example.experiment.domain.Experiment;
import com.example.experiment.domain.ExperimentState;
import com.example.experiment.domain.ExperimentVersion;
import com.example.experiment.domain.FieldChange;
import com.example.experiment.domain.LifecycleAction;
import com.example.experiment.domain.Split;
import com.example.experiment.store.InMemoryExperimentRepository;
import com.example.experiment.validation.ExperimentValidator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExperimentServiceTest {

    private MutableClock clock;
    private ExperimentService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new ExperimentService(new InMemoryExperimentRepository(),
                new ExperimentValidator(InMemoryProductCatalogue.sample()), clock);
    }

    private Experiment running() {
        Experiment e = service.create(complete(), "maria");
        service.apply(e.id(), LifecycleAction.PUBLISH, null, "maria", null);
        clock.set(START);
        service.tick();
        return service.get(e.id());
    }

    private static ExperimentException expect(String code, Runnable r) {
        ExperimentException e = assertThrows(ExperimentException.class, r::run);
        assertEquals(code, e.code(), e.getMessage());
        return e;
    }

    @Test
    void createStartsAsDraftVersionOne() {
        Experiment e = service.create(detailsOnly(), "maria");
        assertEquals("EXP-000001", e.id());
        assertEquals(ExperimentState.DRAFT, e.state());
        assertEquals(1, e.version());
        ExperimentVersion v1 = service.version(e.id(), 1);
        assertEquals(ChangeType.CREATED, v1.changeType());
        assertEquals("maria", v1.changedBy());
        assertEquals(NOW, v1.changedAt());
    }

    @Test
    void createRejectsInvalidDetailsWithoutSaving() {
        ExperimentException ex = expect(ExperimentException.VALIDATION_FAILED,
                () -> service.create(withSplit(detailsOnly(), new Split(90, 20)), "maria"));
        assertEquals(400, ex.status());
        assertEquals(List.of(), service.list(null));
    }

    @Test
    void userIsRequired() {
        expect(ExperimentException.VALIDATION_FAILED, () -> service.create(detailsOnly(), " "));
    }

    @Test
    void editCreatesNewVersionWithFieldChanges() {
        Experiment e = service.create(detailsOnly(), "maria");
        clock.advance(Duration.ofHours(1));
        Experiment updated = service.update(e.id(), 1L, complete(), "li");
        assertEquals(2, updated.version());
        assertEquals(2, updated.configVersion());

        ExperimentVersion v2 = service.version(e.id(), 2);
        assertEquals(ChangeType.UPDATED, v2.changeType());
        assertEquals("li", v2.changedBy());
        assertTrue(v2.changes().contains(new FieldChange("split.testPercent", null, "90")));
        assertTrue(v2.changes().contains(new FieldChange("split.controlPercent", null, "10")));
        assertTrue(v2.changes().contains(new FieldChange("bucketing.primary", null, "USER_ID")));
        // version 1 is untouched
        assertNull(service.version(e.id(), 1).config().split());
    }

    @Test
    void extendingEndDateInDraftIsVersionTwo() {
        Experiment e = service.create(detailsOnly(), "maria");
        var d = detailsOnly();
        var extended = new com.example.experiment.domain.ExperimentConfig(d.name(), d.description(), d.startAt(),
                END.plus(Duration.ofDays(7)), d.treatmentType(), d.priority(), null, null, null);
        service.update(e.id(), null, extended, "li");
        List<FieldChange> changes = service.version(e.id(), 2).changes();
        assertEquals(List.of(new FieldChange("endAt", "2027-02-01T00:00:00Z", "2027-02-08T00:00:00Z")), changes);
    }

    @Test
    void unchangedEditCreatesNoVersion() {
        Experiment e = service.create(complete(), "maria");
        assertSame(e, service.update(e.id(), 1L, complete(), "li"));
        assertEquals(1, service.versions(e.id()).size());
    }

    @Test
    void staleVersionIsRejected() {
        Experiment e = service.create(detailsOnly(), "maria");
        service.update(e.id(), 1L, complete(), "li");
        expect(ExperimentException.VERSION_CONFLICT,
                () -> service.update(e.id(), 1L, withPriority(complete(), 5), "maria"));
    }

    @Test
    void publishRequiresEverySection() {
        Experiment e = service.create(detailsOnly(), "maria");
        ExperimentException ex = expect(ExperimentException.VALIDATION_FAILED,
                () -> service.apply(e.id(), LifecycleAction.PUBLISH, null, "maria", null));
        assertEquals(3, ex.errors().size());
        assertEquals(ExperimentState.DRAFT, service.get(e.id()).state());
    }

    @Test
    void cannotJumpFromDraftToRunningOrPause() {
        Experiment e = service.create(complete(), "maria");
        expect(ExperimentException.INVALID_TRANSITION,
                () -> service.apply(e.id(), LifecycleAction.START, null, "maria", null));
        ExperimentException ex = expect(ExperimentException.INVALID_TRANSITION,
                () -> service.apply(e.id(), LifecycleAction.PAUSE, null, "maria", null));
        assertTrue(ex.getMessage().contains("Allowed actions: publish"), ex.getMessage());
    }

    @Test
    void scheduledMustBeUnpublishedBeforeEditing() {
        Experiment e = service.create(complete(), "maria");
        service.apply(e.id(), LifecycleAction.PUBLISH, null, "maria", null);
        expect(ExperimentException.EXPERIMENT_LOCKED,
                () -> service.update(e.id(), null, withPriority(complete(), 5), "maria"));
        service.apply(e.id(), LifecycleAction.UNPUBLISH, null, "maria", "fix priority");
        Experiment edited = service.update(e.id(), null, withPriority(complete(), 5), "maria");
        assertEquals(5, edited.config().priority());
    }

    @Test
    void schedulerStartsAtStartTime() {
        Experiment e = service.create(complete(), "maria");
        service.apply(e.id(), LifecycleAction.PUBLISH, null, "maria", null);
        clock.set(START.minusSeconds(1));
        assertEquals(0, service.tick());
        clock.set(START);
        assertEquals(1, service.tick());
        assertEquals(0, service.tick(), "tick is idempotent");
        Experiment running = service.get(e.id());
        assertEquals(ExperimentState.RUNNING, running.state());
        ExperimentVersion last = service.version(e.id(), running.version());
        assertEquals(ExperimentService.SYSTEM_USER, last.changedBy());
        assertEquals(LifecycleAction.START, last.action());
    }

    @Test
    void runningLockRejectsEveryEdit() {
        Experiment e = running();
        for (var change : List.of(withSplit(complete(), new Split(50, 50)), withPriority(complete(), 99))) {
            ExperimentException ex = expect(ExperimentException.EXPERIMENT_LOCKED,
                    () -> service.update(e.id(), null, change, "maria"));
            assertEquals(409, ex.status());
            assertTrue(ex.getMessage().contains("end this experiment and create a new one"));
        }
        service.apply(e.id(), LifecycleAction.PAUSE, null, "maria", null);
        expect(ExperimentException.EXPERIMENT_LOCKED,
                () -> service.update(e.id(), null, withPriority(complete(), 99), "maria"));
        assertEquals(e.version() + 1, service.get(e.id()).version(), "rejected edits create no version");
    }

    @Test
    void pausedExperimentServesDefaultAndResumesWithSameConfigVersion() {
        Experiment e = running();
        assertEquals(1, service.activeExperiments().size());

        clock.advance(Duration.ofDays(1));
        Experiment paused = service.apply(e.id(), LifecycleAction.PAUSE, null, "maria", "check revenue");
        assertEquals(ExperimentState.PAUSED, paused.state());
        assertEquals(List.of(), service.activeExperiments());
        assertEquals("check revenue", service.version(e.id(), paused.version()).reason());

        clock.advance(Duration.ofDays(1));
        Experiment resumed = service.apply(e.id(), LifecycleAction.RESUME, null, "maria", null);
        assertEquals(ExperimentState.RUNNING, resumed.state());
        assertEquals(e.configVersion(), resumed.configVersion());
        assertEquals(e.configVersion(), service.activeExperiments().get(0).configVersion());
    }

    @Test
    void endedIsReadOnly() {
        Experiment e = running();
        service.apply(e.id(), LifecycleAction.END, null, "maria", "enough data");
        expect(ExperimentException.READ_ONLY, () -> service.update(e.id(), null, complete(), "maria"));
        expect(ExperimentException.READ_ONLY,
                () -> service.apply(e.id(), LifecycleAction.RESUME, null, "maria", null));
    }

    @Test
    void schedulerEndsRunningAndPausedAtEndTime() {
        Experiment a = running();
        Experiment b = service.create(complete(), "li");
        service.apply(b.id(), LifecycleAction.PUBLISH, null, "li", null);
        service.tick();
        service.apply(b.id(), LifecycleAction.PAUSE, null, "li", null);
        clock.set(END);
        assertEquals(2, service.tick());
        assertEquals(ExperimentState.ENDED, service.get(a.id()).state());
        assertEquals(ExperimentState.ENDED, service.get(b.id()).state());
    }

    @Test
    void resumeAfterEndTimeIsRejected() {
        Experiment e = running();
        service.apply(e.id(), LifecycleAction.PAUSE, null, "maria", null);
        clock.set(END);
        expect(ExperimentException.INVALID_TRANSITION,
                () -> service.apply(e.id(), LifecycleAction.RESUME, null, "maria", null));
    }

    @Test
    void servedAtTracesTrafficToConfigVersion() {
        Experiment e = service.create(detailsOnly(), "maria");
        service.update(e.id(), null, complete(), "maria");               // config version 2
        service.apply(e.id(), LifecycleAction.PUBLISH, null, "maria", null);
        clock.set(START);
        service.tick();
        clock.set(START.plus(Duration.ofDays(2)));
        service.apply(e.id(), LifecycleAction.PAUSE, null, "maria", null);

        ServedVersion before = service.servedAt(e.id(), NOW.minusSeconds(1));
        assertNull(before.state());
        assertTrue(before.defaultExperience());

        ServedVersion scheduled = service.servedAt(e.id(), START.minusSeconds(1));
        assertEquals(ExperimentState.SCHEDULED, scheduled.state());
        assertTrue(scheduled.defaultExperience());

        ServedVersion live = service.servedAt(e.id(), START.plus(Duration.ofDays(1)));
        assertEquals(ExperimentState.RUNNING, live.state());
        assertFalse(live.defaultExperience());
        assertEquals(2L, live.configVersion());
        assertEquals(90, live.config().split().testPercent());

        ServedVersion paused = service.servedAt(e.id(), START.plus(Duration.ofDays(3)));
        assertEquals(ExperimentState.PAUSED, paused.state());
        assertTrue(paused.defaultExperience());
    }

    @Test
    void activeFeedIsOrderedByPriority() {
        Experiment low = service.create(withPriority(complete(), 5), "maria");
        Experiment high = service.create(withPriority(complete(), 50), "maria");
        Experiment draft = service.create(withPriority(complete(), 999), "maria");
        service.apply(low.id(), LifecycleAction.PUBLISH, null, "maria", null);
        service.apply(high.id(), LifecycleAction.PUBLISH, null, "maria", null);
        clock.set(START);
        service.tick();
        List<ActiveExperiment> feed = service.activeExperiments();
        assertEquals(List.of(high.id(), low.id()), feed.stream().map(ActiveExperiment::experimentId).toList());
        assertFalse(feed.stream().anyMatch(a -> a.experimentId().equals(draft.id())));
        ActiveExperiment top = feed.get(0);
        assertEquals("USER_ID", top.primaryIdentifier().name());
        assertEquals(2, top.fallbackOrder().size());
    }

    @Test
    void everyStateChangeIsRecordedWithUserAndTime() {
        Experiment e = running();
        clock.advance(Duration.ofMinutes(5));
        service.apply(e.id(), LifecycleAction.END, null, "sam", null);
        List<ExperimentVersion> history = service.versions(e.id());
        assertEquals(List.of(ChangeType.CREATED, ChangeType.STATE_CHANGED, ChangeType.STATE_CHANGED,
                ChangeType.STATE_CHANGED), history.stream().map(ExperimentVersion::changeType).toList());
        ExperimentVersion last = history.get(history.size() - 1);
        assertEquals("sam", last.changedBy());
        assertEquals(START.plus(Duration.ofMinutes(5)), last.changedAt());
        assertEquals(List.of(new FieldChange("state", "RUNNING", "ENDED")), last.changes());
    }
}
