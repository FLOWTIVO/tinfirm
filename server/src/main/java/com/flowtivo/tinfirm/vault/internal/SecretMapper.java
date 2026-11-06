package com.flowtivo.tinfirm.vault.internal;

import java.util.Map;
import java.util.UUID;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.flowtivo.tinfirm.shared.api.model.SecretMeta;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

/**
 * Maps secret metadata. There is deliberately no mapping that could carry a value. {@code managed} and {@code
 * managedBy} follow the name ({@link ManagedSecrets}).
 */
@Mapper(config = ApiMapperConfig.class)
interface SecretMapper {

    String DELETED_USER = "deleted user";

    @Mapping(target = "version", source = "currentVersion")
    @Mapping(target = "createdBy", expression = "java(creator(secret, displayNames))")
    @Mapping(target = "managed", expression = "java(ManagedSecrets.managed(secret.getName()))")
    @Mapping(target = "managedBy", expression = "java(ManagedSecrets.managedBy(secret.getName()))")
    SecretMeta toDto(SecretEntity secret, @Context Map<UUID, String> displayNames);

    default String creator(SecretEntity secret, Map<UUID, String> displayNames) {
        UUID createdBy = secret.getCreatedBy();
        return createdBy != null ? displayNames.getOrDefault(createdBy, DELETED_USER) : DELETED_USER;
    }
}
