package com.example.experiment.catalogue;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A fixed catalogue, for local runs and tests. */
public final class InMemoryProductCatalogue implements ProductCatalogue {

    private final Set<String> products;
    private final Set<String> fareBrands;

    public InMemoryProductCatalogue(Collection<String> products, Collection<String> fareBrands) {
        this.products = Collections.unmodifiableSet(new LinkedHashSet<>(products));
        this.fareBrands = Collections.unmodifiableSet(new LinkedHashSet<>(fareBrands));
    }

    /** Sample airline ancillaries and fare brands. */
    public static InMemoryProductCatalogue sample() {
        return new InMemoryProductCatalogue(
                List.of("FLIGHT", "BAG_EXTRA_23KG", "BAG_EXTRA_32KG", "SEAT_STANDARD", "SEAT_EXTRA_LEGROOM",
                        "SEAT_UPGRADE", "SEAT_BAG_BUNDLE", "MEAL", "PRIORITY_BOARDING", "LOUNGE"),
                List.of("LIGHT", "STANDARD", "FLEX", "BUSINESS"));
    }

    @Override
    public boolean hasProduct(String productCode) {
        return products.contains(productCode);
    }

    @Override
    public boolean hasFareBrand(String fareBrandCode) {
        return fareBrands.contains(fareBrandCode);
    }

    @Override
    public Set<String> productCodes() {
        return products;
    }

    @Override
    public Set<String> fareBrandCodes() {
        return fareBrands;
    }
}
