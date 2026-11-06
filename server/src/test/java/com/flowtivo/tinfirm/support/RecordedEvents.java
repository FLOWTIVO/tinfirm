package com.flowtivo.tinfirm.support;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The domain events of Tinfirm's modules as a listener sees them after the commit (the modules' {@code *Events}
 * classes), so tests can prove what was announced, and that a change which rolled back announced nothing. Picked up by
 * the application's component scan in every integration test's context; events are told apart by their company.
 */
@Component
public class RecordedEvents {

    private static final List<Object> EVENTS = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void afterCommit(Object event) {
        if (event.getClass().getName().startsWith("com.flowtivo.tinfirm.") && event.getClass().isRecord()) {
            EVENTS.add(event);
        }
    }

    /** The events of the company's in the order they were committed, of the given type. */
    public static <T> List<T> of(UUID companyId, Class<T> type) {
        return EVENTS.stream()
            .filter(type::isInstance)
            .map(type::cast)
            .filter(event -> companyId.equals(companyOf(event)))
            .toList();
    }

    private static Object companyOf(Object event) {
        for (RecordComponent component : event.getClass().getRecordComponents()) {
            if (component.getName().equals("companyId")) {
                try {
                    return component.getAccessor().invoke(event);
                }
                catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
        return null;
    }
}
