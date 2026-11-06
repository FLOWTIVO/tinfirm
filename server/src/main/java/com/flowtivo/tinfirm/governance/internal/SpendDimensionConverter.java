package com.flowtivo.tinfirm.governance.internal;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.api.model.SpendDimension;

/**
 * {@code groupBy} names its dimensions in lower case, as the contract writes them ({@code payer}, {@code model}); the
 * default conversion would want the constants' names. An unknown value is {@code 400 VALIDATION_FAILED}.
 */
@Component
class SpendDimensionConverter implements Converter<String, SpendDimension> {

    @Override
    public SpendDimension convert(String source) {
        return SpendDimension.fromValue(source.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
