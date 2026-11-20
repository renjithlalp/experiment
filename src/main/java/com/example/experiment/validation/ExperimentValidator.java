package com.example.experiment.validation;

import com.example.experiment.catalogue.ProductCatalogue;
import com.example.experiment.domain.Bucketing;
import com.example.experiment.domain.BucketingIdentifier;
import com.example.experiment.domain.ExperimentConfig;
import com.example.experiment.domain.PricingAdjustment;
import com.example.experiment.domain.PricingTreatment;
import com.example.experiment.domain.Split;
import com.example.experiment.domain.TreatmentType;
import com.example.experiment.domain.ValueType;
import com.example.experiment.service.FieldError;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Business rules for an experiment configuration. Every rule is checked and every failure reported, so the user
 * can fix all problems at once.
 *
 * <ul>
 *   <li>{@link #checkSave} runs on every create and edit: details are mandatory, and any section that is present
 *       must be valid.</li>
 *   <li>{@link #checkPublish} also requires every section to be present, before the experiment can be scheduled.</li>
 * </ul>
 */
public final class ExperimentValidator {

    public static final int NAME_MAX = 100;
    public static final int DESCRIPTION_MAX = 500;
    public static final int PRIORITY_MIN = 1;
    public static final int PRIORITY_MAX = 1000;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_MARKUP_PERCENT = BigDecimal.valueOf(1000);
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final ProductCatalogue catalogue;

    public ExperimentValidator(ProductCatalogue catalogue) {
        this.catalogue = Objects.requireNonNull(catalogue);
    }

    public List<FieldError> checkSave(ExperimentConfig c) {
        List<FieldError> errors = new ArrayList<>();
        if (c == null) {
            errors.add(new FieldError("body", "experiment details are required"));
            return errors;
        }
        checkDetails(c, errors);
        if (c.split() != null) {
            checkSplit(c.split(), errors);
        }
        if (c.bucketing() != null) {
            checkBucketing(c.bucketing(), errors);
        }
        if (c.pricing() != null) {
            if (c.treatmentType() != null && c.treatmentType() != TreatmentType.PRICING) {
                errors.add(new FieldError("pricing",
                        "pricing settings only apply when treatmentType is PRICING"));
            } else {
                checkPricing(c.pricing(), errors);
            }
        }
        return errors;
    }

    public List<FieldError> checkPublish(ExperimentConfig c, Instant now) {
        List<FieldError> errors = checkSave(c);
        if (c == null) {
            return errors;
        }
        if (c.split() == null) {
            errors.add(new FieldError("split", "a test/control split is required before publishing"));
        }
        if (c.bucketing() == null) {
            errors.add(new FieldError("bucketing", "a bucketing identifier is required before publishing"));
        }
        if (c.treatmentType() == TreatmentType.PRICING && c.pricing() == null) {
            errors.add(new FieldError("pricing", "a pricing treatment is required before publishing"));
        }
        if (c.endAt() != null && !c.endAt().isAfter(now)) {
            errors.add(new FieldError("endAt", "endAt must be in the future to publish"));
        }
        return errors;
    }

    private void checkDetails(ExperimentConfig c, List<FieldError> errors) {
        if (c.name() == null || c.name().isBlank()) {
            errors.add(new FieldError("name", "name is required"));
        } else if (c.name().length() > NAME_MAX) {
            errors.add(new FieldError("name", "name must be at most " + NAME_MAX + " characters"));
        }
        if (c.description() != null && c.description().length() > DESCRIPTION_MAX) {
            errors.add(new FieldError("description", "description must be at most " + DESCRIPTION_MAX + " characters"));
        }
        if (c.startAt() == null) {
            errors.add(new FieldError("startAt", "startAt is required"));
        }
        if (c.endAt() == null) {
            errors.add(new FieldError("endAt", "endAt is required"));
        }
        if (c.startAt() != null && c.endAt() != null && !c.endAt().isAfter(c.startAt())) {
            errors.add(new FieldError("endAt", "endAt must be after startAt"));
        }
        if (c.treatmentType() == null) {
            errors.add(new FieldError("treatmentType",
                    "treatmentType is required (allowed: " + Arrays.toString(TreatmentType.values()) + ")"));
        }
        if (c.priority() == null) {
            errors.add(new FieldError("priority", "priority is required"));
        } else if (c.priority() < PRIORITY_MIN || c.priority() > PRIORITY_MAX) {
            errors.add(new FieldError("priority",
                    "priority must be between " + PRIORITY_MIN + " and " + PRIORITY_MAX));
        }
    }

    private void checkSplit(Split s, List<FieldError> errors) {
        if (s.controlPercent() == null) {
            errors.add(new FieldError("split.controlPercent",
                    "controlPercent is required: the control group is mandatory and cannot be removed"));
        } else if (s.controlPercent() <= 0) {
            errors.add(new FieldError("split.controlPercent", "the control group is mandatory and must be above 0%"));
        }
        if (s.testPercent() == null) {
            errors.add(new FieldError("split.testPercent", "testPercent is required"));
        } else if (s.testPercent() <= 0) {
            errors.add(new FieldError("split.testPercent", "the test group must be above 0%"));
        }
        if (s.testPercent() != null && s.controlPercent() != null && s.testPercent() + s.controlPercent() != 100) {
            errors.add(new FieldError("split", "testPercent + controlPercent must equal 100 (got "
                    + (s.testPercent() + s.controlPercent()) + ")"));
        }
    }

    private void checkBucketing(Bucketing b, List<FieldError> errors) {
        if (b.primary() == null) {
            errors.add(new FieldError("bucketing.primary", "a primary identifier is required (allowed: "
                    + Arrays.toString(BucketingIdentifier.values()) + ")"));
        }
        Set<BucketingIdentifier> seen = EnumSet.noneOf(BucketingIdentifier.class);
        for (BucketingIdentifier id : b.fallbackOrder()) {
            if (id == null) {
                errors.add(new FieldError("bucketing.fallbackOrder", "fallbackOrder must not contain empty entries"));
            } else if (id == b.primary()) {
                errors.add(new FieldError("bucketing.fallbackOrder",
                        "fallbackOrder must not contain the primary identifier " + id));
            } else if (!seen.add(id)) {
                errors.add(new FieldError("bucketing.fallbackOrder", "fallbackOrder lists " + id + " more than once"));
            }
        }
    }

    private void checkPricing(PricingTreatment p, List<FieldError> errors) {
        if (p.adjustment() == null) {
            errors.add(new FieldError("pricing.adjustment", "adjustment is required (allowed: "
                    + Arrays.toString(PricingAdjustment.values()) + ")"));
        }
        if (p.value() == null) {
            errors.add(new FieldError("pricing.value", "value is required"));
        } else if (p.value().signum() <= 0) {
            errors.add(new FieldError("pricing.value", "value must be above 0"));
        }
        if (p.adjustment() == PricingAdjustment.FLAT_RATE) {
            if (p.valueType() == ValueType.PERCENT) {
                errors.add(new FieldError("pricing.valueType", "a flat rate is a fixed price; valueType must be AMOUNT"));
            }
        } else if (p.adjustment() != null) {
            if (p.valueType() == null) {
                errors.add(new FieldError("pricing.valueType", "valueType is required for " + p.adjustment()
                        + " (allowed: " + Arrays.toString(ValueType.values()) + ")"));
            } else if (p.valueType() == ValueType.PERCENT && p.value() != null) {
                if (p.adjustment() == PricingAdjustment.DISCOUNT && p.value().compareTo(HUNDRED) > 0) {
                    errors.add(new FieldError("pricing.value", "a percentage discount cannot exceed 100"));
                }
                if (p.adjustment() == PricingAdjustment.MARKUP && p.value().compareTo(MAX_MARKUP_PERCENT) > 0) {
                    errors.add(new FieldError("pricing.value", "a percentage markup cannot exceed " + MAX_MARKUP_PERCENT));
                }
            }
        }
        if (p.currency() == null || !CURRENCY.matcher(p.currency()).matches()) {
            errors.add(new FieldError("pricing.currency", "currency is required as a 3-letter ISO 4217 code, e.g. EUR"));
        }
        checkGuardrails(p, errors);
        checkCatalogue(p, errors);
    }

    private void checkGuardrails(PricingTreatment p, List<FieldError> errors) {
        if (p.floor() == null) {
            errors.add(new FieldError("pricing.floor", "floor is required"));
        } else if (p.floor().signum() < 0) {
            errors.add(new FieldError("pricing.floor", "floor cannot be negative"));
        }
        if (p.ceiling() == null) {
            errors.add(new FieldError("pricing.ceiling", "ceiling is required"));
        }
        if (p.floor() != null && p.ceiling() != null && p.floor().compareTo(p.ceiling()) >= 0) {
            errors.add(new FieldError("pricing.floor", "floor must be below ceiling (floor "
                    + p.floor().toPlainString() + ", ceiling " + p.ceiling().toPlainString() + ")"));
        }
        if (p.adjustment() == PricingAdjustment.FLAT_RATE && p.value() != null && p.floor() != null
                && p.ceiling() != null
                && (p.value().compareTo(p.floor()) < 0 || p.value().compareTo(p.ceiling()) > 0)) {
            errors.add(new FieldError("pricing.value", "flat rate " + p.value().toPlainString()
                    + " must lie between floor " + p.floor().toPlainString() + " and ceiling "
                    + p.ceiling().toPlainString()));
        }
    }

    private void checkCatalogue(PricingTreatment p, List<FieldError> errors) {
        if (p.productCodes().isEmpty()) {
            errors.add(new FieldError("pricing.productCodes", "at least one product code is required"));
        }
        Set<String> unknownProducts = new LinkedHashSet<>();
        for (String code : p.productCodes()) {
            if (code == null || !catalogue.hasProduct(code)) {
                unknownProducts.add(String.valueOf(code));
            }
        }
        if (!unknownProducts.isEmpty()) {
            errors.add(new FieldError("pricing.productCodes",
                    "not in the product catalogue: " + String.join(", ", unknownProducts)));
        }
        Set<String> unknownBrands = new LinkedHashSet<>();
        for (String code : p.fareBrands()) {
            if (code == null || !catalogue.hasFareBrand(code)) {
                unknownBrands.add(String.valueOf(code));
            }
        }
        if (!unknownBrands.isEmpty()) {
            errors.add(new FieldError("pricing.fareBrands",
                    "not in the product catalogue: " + String.join(", ", unknownBrands)));
        }
    }
}
