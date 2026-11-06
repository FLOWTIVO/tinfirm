package com.flowtivo.tinfirm.audit.internal;

import java.util.Map;
import java.util.UUID;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.flowtivo.tinfirm.shared.api.model.AuditActor;
import com.flowtivo.tinfirm.shared.api.model.AuditEvent;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

import tools.jackson.databind.json.JsonMapper;

@Mapper(config = ApiMapperConfig.class)
interface AuditEventMapper {

    String SYSTEM_DISPLAY = "system";

    String ANONYMOUS_DISPLAY = "anonymous";

    String DELETED_USER_DISPLAY = "deleted user";

    String DELETED_AGENT_DISPLAY = "deleted agent";

    String SCIM_TOKEN_DISPLAY = "SCIM token";

    JsonMapper DETAILS_READER = JsonMapper.builder().build();

    @Mapping(target = "actor", expression = "java(actor(event, names))")
    @Mapping(target = "targetName", expression = "java(names.target(event))")
    @Mapping(target = "details", expression = "java(details(event))")
    AuditEvent toDto(StoredAuditEvent event, @Context AuditNames.Names names);

    /**
     * The display name is resolved now, not stored: a user that no longer exists shows as "deleted user", an erased
     * person in a company's chain as "erased person" ({@link AuditNames}), an agent actor (CONVENTIONS §8) by its name,
     * or "deleted agent".
     */
    default AuditActor actor(StoredAuditEvent event, AuditNames.Names names) {
        Map<UUID, String> displayNames = names.actors();
        AuditActor.TypeEnum type = AuditActor.TypeEnum.fromValue(event.actorType());
        UUID id = event.actorId();
        String display;
        if (type == AuditActor.TypeEnum.SYSTEM) {
            display = SYSTEM_DISPLAY;
        }
        else if (id == null) {
            display = ANONYMOUS_DISPLAY;
        }
        else if (type == AuditActor.TypeEnum.USER && names.erasedUsers().contains(id)) {
            display = AuditNames.ERASED_PERSON;
        }
        else if (type == AuditActor.TypeEnum.AGENT) {
            display = displayNames.getOrDefault(id, DELETED_AGENT_DISPLAY);
        }
        else if (type == AuditActor.TypeEnum.SCIM) {
            display = displayNames.getOrDefault(id, SCIM_TOKEN_DISPLAY);
        }
        else {
            display = displayNames.getOrDefault(id, DELETED_USER_DISPLAY);
        }
        return new AuditActor(type, display).id(id);
    }

    @SuppressWarnings("unchecked")
    default Map<String, Object> details(StoredAuditEvent event) {
        return DETAILS_READER.readValue(event.detailsJson(), Map.class);
    }
}
