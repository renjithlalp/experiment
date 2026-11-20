package com.example.experiment.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The price change the test group receives, with its guardrails.
 *
 * @param adjustment   discount, markup or flat rate
 * @param valueType    percent or amount (a flat rate is always an amount)
 * @param value        size of the discount or markup, or the flat price
 * @param currency     ISO 4217 code that amounts, floor and ceiling are expressed in
 * @param floor        lowest price the test may produce
 * @param ceiling      highest price the test may produce
 * @param productCodes catalogue products the treatment applies to
 * @param fareBrands   optional catalogue fare brands that narrow the treatment
 */
public record PricingTreatment(
        PricingAdjustment adjustment,
        ValueType valueType,
        BigDecimal value,
        String currency,
        BigDecimal floor,
        BigDecimal ceiling,
        List<String> productCodes,
        List<String> fareBrands) {

    public PricingTreatment {
        // Normalise scale so 10 and 10.00 compare equal and do not show up as a change between versions
        value = normalise(value);
        floor = normalise(floor);
        ceiling = normalise(ceiling);
        productCodes = copy(productCodes);
        fareBrands = copy(fareBrands);
    }

    private static BigDecimal normalise(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static List<String> copy(List<String> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }
}
