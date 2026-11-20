package com.example.experiment.catalogue;

import java.util.Set;

/**
 * The product catalogue that pricing treatments may refer to. Experiments may only use products and fare
 * brands known here. The real catalogue service plugs in behind this interface.
 */
public interface ProductCatalogue {

    boolean hasProduct(String productCode);

    boolean hasFareBrand(String fareBrandCode);

    Set<String> productCodes();

    Set<String> fareBrandCodes();
}
