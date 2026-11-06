package com.flowtivo.tinfirm.runner.internal;

import org.mapstruct.Mapper;

import com.flowtivo.tinfirm.shared.api.model.RunnerHost;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

@Mapper(config = ApiMapperConfig.class)
interface RunnerMapper {

    RunnerHost toDto(RunnerViews.HostDetails host);
}
