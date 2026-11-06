package com.flowtivo.tinfirm.agent;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.MemoryNote;
import com.flowtivo.tinfirm.shared.api.model.MemoryNotes;

/** A memory as the API shows it (ADR-0063), for the agent's and the trigger's pages alike. */
public final class MemoryViews {

    private MemoryViews() {
    }

    public static MemoryNotes of(AgentMemory.Notes notes) {
        return new MemoryNotes(notes.inEffect(), notes.notes()
            .stream()
            .map(MemoryViews::note)
            .collect(Collectors.toCollection(ArrayList::new)), notes.characters(), notes.maxCharacters())
            .compactedAt(ts(notes.compactedAt()));
    }

    private static MemoryNote note(AgentMemory.Note note) {
        MemoryNote view = new MemoryNote(note.number(), note.id(), MemoryNote.StateEnum.fromValue(note.state().name()),
                MemoryNote.AboutEnum.fromValue(switch (note.about()) {
                    case AgentMemory.About.General general -> "GENERAL";
                    case AgentMemory.About.System system -> "SYSTEM";
                    case AgentMemory.About.Repository repository -> "REPOSITORY";
                }), note.body(), ts(note.createdAt()), ts(note.updatedAt()), ts(note.usedAt()))
            .replaces(note.replaces())
            .conversationId(note.conversationId());
        if (note.about() instanceof AgentMemory.About.System system) {
            view.systemId(system.systemId());
        }
        if (note.about() instanceof AgentMemory.About.Repository repository) {
            view.repositoryId(repository.repositoryId());
        }
        return view;
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
