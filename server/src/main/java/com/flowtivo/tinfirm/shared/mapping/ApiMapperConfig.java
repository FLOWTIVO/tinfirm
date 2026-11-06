package com.flowtivo.tinfirm.shared.mapping;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.MapperConfig;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Shared MapStruct configuration for mapping domain data to the generated API DTOs. */
@MapperConfig(componentModel = MappingConstants.ComponentModel.SPRING,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        uses = { TimeMapping.class })
public interface ApiMapperConfig {
}
