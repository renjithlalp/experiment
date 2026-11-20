package com.example.experiment.validation;

import static com.example.experiment.Fixtures.END;
import static com.example.experiment.Fixtures.NOW;
import static com.example.experiment.Fixtures.START;
import static com.example.experiment.Fixtures.complete;
import static com.example.experiment.Fixtures.detailsOnly;
import static com.example.experiment.Fixtures.discount10;
import static com.example.experiment.Fixtures.withEnd;
import static com.example.experiment.Fixtures.withPricing;
import static com.example.experiment.Fixtures.withPriority;
import static com.example.experiment.Fixtures.withSplit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.experiment.catalogue.InMemoryProductCatalogue;
import com.example.experiment.domain.Bucketing;
import com.example.experiment.domain.BucketingIdentifier;
import com.example.experiment.domain.ExperimentConfig;
import com.example.experiment.domain.PricingAdjustment;
import com.example.experiment.domain.PricingTreatment;
import com.example.experiment.domain.Split;
import com.example.experiment.domain.ValueType;
import com.example.experiment.service.FieldError;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExperimentValidatorTest {

    private final ExperimentValidator validator = new ExperimentValidator(InMemoryProductCatalogue.sample());

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    private static boolean has(List<FieldError> errors, String field, String messagePart) {
        return errors.stream().anyMatch(e -> e.field().equals(field) && e.message().contains(messagePart));
    }

    @Test
    void completeConfigurationIsValidForSaveAndPublish() {
        assertEquals(List.of(), validator.checkSave(complete()));
        assertEquals(List.of(), validator.checkPublish(complete(), NOW));
    }

    @Test
    void detailsAloneCanBeSavedButNotPublished() {
        assertEquals(List.of(), validator.checkSave(detailsOnly()));
        assertEquals(List.of("split", "bucketing", "pricing"), fields(validator.checkPublish(detailsOnly(), NOW)));
    }

    @Test
    void everyMissingDetailIsReported() {
        ExperimentConfig empty = new ExperimentConfig(" ", null, null, null, null, null, null, null, null);
        assertEquals(List.of("name", "startAt", "endAt", "treatmentType", "priority"),
                fields(validator.checkSave(empty)));
    }

    @Test
    void endMustBeAfterStart() {
        assertTrue(has(validator.checkSave(withEnd(detailsOnly(), START)), "endAt", "after startAt"));
        assertTrue(has(validator.checkSave(withEnd(detailsOnly(), START.minusSeconds(1))), "endAt", "after startAt"));
    }

    @Test
    void publishNeedsEndInTheFuture() {
        assertTrue(has(validator.checkPublish(complete(), END), "endAt", "future"));
    }

    @Test
    void priorityMustBeInRange() {
        assertTrue(has(validator.checkSave(withPriority(detailsOnly(), 0)), "priority", "between"));
        assertTrue(has(validator.checkSave(withPriority(detailsOnly(), 1001)), "priority", "between"));
        assertEquals(List.of(), validator.checkSave(withPriority(detailsOnly(), 1000)));
    }

    @Test
    void splitMustTotal100() {
        assertTrue(has(validator.checkSave(withSplit(complete(), new Split(90, 20))), "split", "must equal 100"));
        assertEquals(List.of(), validator.checkSave(withSplit(complete(), new Split(50, 50))));
    }

    @Test
    void controlGroupIsMandatoryAndAboveZero() {
        assertTrue(has(validator.checkSave(withSplit(complete(), new Split(100, 0))), "split.controlPercent",
                "above 0%"));
        assertTrue(has(validator.checkSave(withSplit(complete(), new Split(100, null))), "split.controlPercent",
                "cannot be removed"));
    }

    @Test
    void testGroupMustBeAboveZero() {
        assertTrue(has(validator.checkSave(withSplit(complete(), new Split(0, 100))), "split.testPercent", "above 0%"));
    }

    @Test
    void fallbackOrderCannotRepeatOrContainPrimary() {
        ExperimentConfig c = complete();
        ExperimentConfig withPrimary = new ExperimentConfig(c.name(), c.description(), c.startAt(), c.endAt(),
                c.treatmentType(), c.priority(), c.split(),
                new Bucketing(BucketingIdentifier.USER_ID, List.of(BucketingIdentifier.USER_ID)), c.pricing());
        assertTrue(has(validator.checkSave(withPrimary), "bucketing.fallbackOrder", "primary"));

        ExperimentConfig duplicate = new ExperimentConfig(c.name(), c.description(), c.startAt(), c.endAt(),
                c.treatmentType(), c.priority(), c.split(), new Bucketing(BucketingIdentifier.USER_ID,
                List.of(BucketingIdentifier.SESSION_ID, BucketingIdentifier.SESSION_ID)), c.pricing());
        assertTrue(has(validator.checkSave(duplicate), "bucketing.fallbackOrder", "more than once"));

        ExperimentConfig noPrimary = new ExperimentConfig(c.name(), c.description(), c.startAt(), c.endAt(),
                c.treatmentType(), c.priority(), c.split(), new Bucketing(null, null), c.pricing());
        assertTrue(has(validator.checkSave(noPrimary), "bucketing.primary", "required"));
    }

    @Test
    void floorMustBeBelowCeiling() {
        PricingTreatment p = pricing(PricingAdjustment.DISCOUNT, ValueType.PERCENT, "10", "120", "100");
        assertTrue(has(validator.checkSave(withPricing(complete(), p)), "pricing.floor", "below ceiling"));
        PricingTreatment equal = pricing(PricingAdjustment.DISCOUNT, ValueType.PERCENT, "10", "100", "100");
        assertTrue(has(validator.checkSave(withPricing(complete(), equal)), "pricing.floor", "below ceiling"));
    }

    @Test
    void flatRateMustLieWithinGuardrails() {
        PricingTreatment above = pricing(PricingAdjustment.FLAT_RATE, ValueType.AMOUNT, "150", "20", "120");
        assertTrue(has(validator.checkSave(withPricing(complete(), above)), "pricing.value", "must lie between"));
        PricingTreatment inside = pricing(PricingAdjustment.FLAT_RATE, null, "49.99", "20", "120");
        assertEquals(List.of(), validator.checkSave(withPricing(complete(), inside)));
        PricingTreatment percent = pricing(PricingAdjustment.FLAT_RATE, ValueType.PERCENT, "50", "20", "120");
        assertTrue(has(validator.checkSave(withPricing(complete(), percent)), "pricing.valueType", "AMOUNT"));
    }

    @Test
    void percentageLimits() {
        PricingTreatment discount = pricing(PricingAdjustment.DISCOUNT, ValueType.PERCENT, "101", "20", "120");
        assertTrue(has(validator.checkSave(withPricing(complete(), discount)), "pricing.value", "cannot exceed 100"));
        PricingTreatment noType = pricing(PricingAdjustment.MARKUP, null, "5", "20", "120");
        assertTrue(has(validator.checkSave(withPricing(complete(), noType)), "pricing.valueType", "required"));
        PricingTreatment zero = pricing(PricingAdjustment.MARKUP, ValueType.AMOUNT, "0", "20", "120");
        assertTrue(has(validator.checkSave(withPricing(complete(), zero)), "pricing.value", "above 0"));
    }

    @Test
    void productsAndFareBrandsMustBeInCatalogue() {
        PricingTreatment p = new PricingTreatment(PricingAdjustment.DISCOUNT, ValueType.PERCENT, BigDecimal.TEN,
                "EUR", BigDecimal.ONE, BigDecimal.TEN, List.of("BAG_EXTRA_23KG", "HOVERCRAFT"), List.of("GOLD"));
        List<FieldError> errors = validator.checkSave(withPricing(complete(), p));
        assertTrue(has(errors, "pricing.productCodes", "HOVERCRAFT"));
        assertTrue(has(errors, "pricing.fareBrands", "GOLD"));

        PricingTreatment none = new PricingTreatment(PricingAdjustment.DISCOUNT, ValueType.PERCENT, BigDecimal.TEN,
                "EUR", BigDecimal.ONE, BigDecimal.TEN, List.of(), null);
        assertTrue(has(validator.checkSave(withPricing(complete(), none)), "pricing.productCodes", "at least one"));
    }

    @Test
    void currencyMustBeIsoCode() {
        PricingTreatment p = new PricingTreatment(PricingAdjustment.DISCOUNT, ValueType.PERCENT, BigDecimal.TEN,
                "euro", BigDecimal.ONE, BigDecimal.TEN, discount10().productCodes(), null);
        assertTrue(has(validator.checkSave(withPricing(complete(), p)), "pricing.currency", "ISO 4217"));
    }

    private static PricingTreatment pricing(PricingAdjustment adj, ValueType type, String value, String floor,
                                            String ceiling) {
        return new PricingTreatment(adj, type, new BigDecimal(value), "EUR", new BigDecimal(floor),
                new BigDecimal(ceiling), List.of("BAG_EXTRA_23KG"), null);
    }
}
