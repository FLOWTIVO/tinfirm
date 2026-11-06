package com.flowtivo.tinfirm.run.internal;

import org.mapstruct.Mapper;

import com.flowtivo.tinfirm.shared.api.model.QueueReason;
import com.flowtivo.tinfirm.shared.api.model.Run;
import com.flowtivo.tinfirm.shared.api.model.RunCounts;
import com.flowtivo.tinfirm.shared.api.model.RunRelayUsage;
import com.flowtivo.tinfirm.shared.api.model.RunRepository;
import com.flowtivo.tinfirm.shared.api.model.RunResult;
import com.flowtivo.tinfirm.shared.api.model.RunUsage;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

@Mapper(config = ApiMapperConfig.class)
interface RunMapper {

    Run toDto(RunViews.RunDetails run);

    RunRepository toDto(RunViews.RepositoryDetails repository);

    QueueReason toDto(RunViews.QueueReasonDetails reason);

    RunResult toDto(RunViews.ResultDetails result);

    RunUsage toDto(RunViews.UsageDetails usage);

    RunRelayUsage toDto(RunViews.RelayDetails relay);

    RunCounts toDto(RunViews.CountsDetails counts);
}
