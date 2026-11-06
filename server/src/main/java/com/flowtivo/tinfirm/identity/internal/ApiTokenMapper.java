package com.flowtivo.tinfirm.identity.internal;

import org.mapstruct.Mapper;

import com.flowtivo.tinfirm.shared.api.model.PersonalAccessToken;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

@Mapper(config = ApiMapperConfig.class)
interface ApiTokenMapper {

    /** Metadata only: the hash, the user and the revocation never leave the server. */
    PersonalAccessToken toDto(ApiTokenEntity token);
}
