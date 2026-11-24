package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import com.flowtivo.tinfirm.shared.tenant.TenantAwareDataSource;

/**
 * Counts the pooled connections each thread holds, and records every time a thread asks the pool for a connection
 * while it already holds one: the outer acquisition's stack and the inner one's. Such a thread needs two connections
 * at once, so as many such threads as the pool has connections wait for each other until the pool's timeout.
 * <p>
 * Every test context gets it ({@link ConnectionWatchCustomizerFactory}). With the environment variable
 * {@code TINFIRM_TEST_CONNECTION_WATCH=report} it writes what it saw to {@code target/connection-watch-<pid>.txt}
 * when the JVM ends; with {@code off} the pool is not wrapped.
 */
public final class ConnectionWatch {

    /** The pooled connections each thread holds, by thread, each with the stack of its acquisition. */
    private static final Map<Thread, Map<Connection, Throwable>> HELD = new ConcurrentHashMap<>();

    /** Each nested acquisition seen, by its condensed outer and inner stacks. */
    private static final Map<Nested, AtomicLong> NESTED = new ConcurrentHashMap<>();

    private static final AtomicLong ACQUISITIONS = new AtomicLong();

    static {
        if ("report".equals(System.getenv("TINFIRM_TEST_CONNECTION_WATCH"))) {
            Runtime.getRuntime().addShutdownHook(new Thread(ConnectionWatch::writeReport));
        }
    }

    private ConnectionWatch() {
    }

    /** A nested acquisition: where the thread took the connection it holds, and where it asked for another. */
    public record Nested(List<String> outer, List<String> inner) {

        @Override
        public String toString() {
            return "  held since:\n" + outer.stream().map(frame -> "      " + frame).collect(Collectors.joining("\n"))
                    + "\n  asked again at:\n"
                    + inner.stream().map(frame -> "      " + frame).collect(Collectors.joining("\n"));
        }
    }

    /** The nested acquisitions seen since the JVM started (or the last {@link #reset()}), with their counts. */
    public static Map<Nested, Long> nested() {
        return NESTED.entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    public static void reset() {
        NESTED.clear();
    }

    /**
     * How many nested acquisitions were seen whose inner stack has a frame that starts with {@code frame} (a class's
     * name, or its name and a method's): for a test that some work asks for no second connection while it holds one.
     */
    public static long nestedAt(String frame) {
        return NESTED.entrySet()
            .stream()
            .filter(entry -> entry.getKey().inner().stream().anyMatch(each -> each.startsWith(frame)))
            .mapToLong(entry -> entry.getValue().get())
            .sum();
    }

    /** What was seen until now, for a test that checks what some work adds ({@link Mark#at}). */
    public static Mark mark() {
        return new Mark(nested());
    }

    /** The nested acquisitions seen until a moment. */
    public record Mark(Map<Nested, Long> seen) {

        /** The nested acquisitions at {@code frame} ({@link ConnectionWatch#nestedAt}) seen since the mark. */
        public long at(String frame) {
            long before = seen.entrySet()
                .stream()
                .filter(entry -> entry.getKey().inner().stream().anyMatch(each -> each.startsWith(frame)))
                .mapToLong(Map.Entry::getValue)
                .sum();
            return nestedAt(frame) - before;
        }
    }

    /** The pooled connections the current thread holds now. */
    public static int heldByCurrentThread() {
        Map<Connection, Throwable> held = HELD.get(Thread.currentThread());
        return held == null ? 0 : held.size();
    }

    static DataSource watch(DataSource target) {
        return new WatchingDataSource(target);
    }

    /** The frames whose acquisitions time out now ({@link #timeOutAt}). */
    private static final List<TimingOut> TIMING_OUT = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The fixtures of the current test class that were never used: a frame's name that matched nothing. */
    private static final List<String> UNUSED = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final String KEPT = "com.flowtivo.tinfirm.shared.tx.AfterTransaction";

    private record TimingOut(String frame, boolean keptOnly, List<String> unless, AtomicLong used) {
    }

    /**
     * TEST FIXTURE: until closed, a connection asked for at a frame that starts with {@code frame} (a class's name, or
     * its name and a method's), and at none of {@code unless}, is refused as the pool refuses one when none is free in
     * time: as if the pool were busy at that moment. One that never refused anything is reported when the test class
     * ends ({@link #endOfClass}).
     */
    public static AutoCloseable timeOutAt(String frame, String... unless) {
        return timingOut(new TimingOut(frame, false, List.of(unless), new AtomicLong()));
    }

    /** {@link #timeOutAt}, for the connections of work kept for after a transaction ({@code AfterTransaction}) only. */
    public static AutoCloseable timeOutKeptWorkAt(String frame) {
        return timingOut(new TimingOut(frame, true, List.of(), new AtomicLong()));
    }

    private static AutoCloseable timingOut(TimingOut timingOut) {
        TIMING_OUT.add(timingOut);
        return () -> {
            if (TIMING_OUT.remove(timingOut) && timingOut.used().get() == 0) {
                UNUSED.add("timeOutAt(" + timingOut.frame() + ")");
            }
        };
    }

    /** The work to run once, on the thread of the next acquisition at a frame ({@link #plantAt}). */
    private static final Map<String, Runnable> PLANTED = new ConcurrentHashMap<>();

    /**
     * TEST FIXTURE: the next connection asked for at a frame that starts with {@code frame} first runs {@code plant}
     * on the asking thread, once: for a test that leaves something on a thread in the middle of real work. One that
     * never ran is dropped, and reported, when the test class ends ({@link #endOfClass}).
     */
    public static void plantAt(String frame, Runnable plant) {
        PLANTED.put(frame, plant);
    }

    /**
     * The end of a test class: every fixture is dropped, so that none outlives its class; the names of those that were
     * never used are answered (the guard fails the class on them).
     */
    public static List<String> endOfClass() {
        List<String> unused = new ArrayList<>(UNUSED);
        UNUSED.clear();
        PLANTED.keySet().forEach(frame -> unused.add("plantAt(" + frame + ")"));
        PLANTED.clear();
        for (TimingOut timingOut : TIMING_OUT) {
            unused.add("timeOutAt(" + timingOut.frame() + "), never closed");
        }
        TIMING_OUT.clear();
        return unused;
    }

    private static boolean at(StackTraceElement[] frames, String frame) {
        return Arrays.stream(frames).anyMatch(each -> (each.getClassName() + "." + each.getMethodName())
            .startsWith(frame));
    }

    private static void plantIfAsked(Throwable stack) {
        if (PLANTED.isEmpty()) {
            return;
        }
        StackTraceElement[] frames = stack.getStackTrace();
        for (String frame : PLANTED.keySet()) {
            if (at(frames, frame)) {
                Runnable plant = PLANTED.remove(frame);
                if (plant != null) {
                    plant.run();
                }
            }
        }
    }

    private static void timeOutIfAsked(Throwable stack) throws SQLException {
        plantIfAsked(stack);
        if (TIMING_OUT.isEmpty()) {
            return;
        }
        StackTraceElement[] frames = stack.getStackTrace();
        boolean kept = Arrays.stream(frames).anyMatch(each -> each.getClassName().startsWith(KEPT));
        for (TimingOut timingOut : TIMING_OUT) {
            if ((kept || !timingOut.keptOnly()) && at(frames, timingOut.frame())
                    && timingOut.unless().stream().noneMatch(frame -> at(frames, frame))) {
                timingOut.used().incrementAndGet();
                throw new java.sql.SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 30000ms (test fixture)");
            }
        }
    }

    /** Before the pool is asked: a thread that holds a connection is recorded, whether or not it gets one. */
    private static Throwable asking() throws SQLException {
        ACQUISITIONS.incrementAndGet();
        // A Throwable's stack is materialised only when it is read: here, for a nested acquisition only.
        Throwable stack = new Throwable();
        timeOutIfAsked(stack);
        Map<Connection, Throwable> held = HELD.get(Thread.currentThread());
        if (held != null) {
            for (Throwable outer : held.values()) {
                NESTED.computeIfAbsent(new Nested(condense(outer), condense(stack)), key -> new AtomicLong())
                    .incrementAndGet();
            }
        }
        return stack;
    }

    private static Connection acquired(Connection target, Throwable stack) {
        Map<Connection, Throwable> held = HELD.computeIfAbsent(Thread.currentThread(),
                thread -> new ConcurrentHashMap<>());
        Thread owner = Thread.currentThread();
        Connection[] self = new Connection[1];
        self[0] = (Connection) Proxy.newProxyInstance(ConnectionWatch.class.getClassLoader(),
                new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close" -> released(owner, self[0]);
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "unwrap" -> {
                            if (((Class<?>) args[0]).isInstance(proxy)) {
                                return proxy;
                            }
                        }
                        case "isWrapperFor" -> {
                            if (((Class<?>) args[0]).isInstance(proxy)) {
                                return true;
                            }
                        }
                        default -> {
                        }
                    }
                    try {
                        return method.invoke(target, args);
                    }
                    catch (InvocationTargetException ex) {
                        throw ex.getTargetException();
                    }
                });
        held.put(self[0], stack);
        return self[0];
    }

    private static void released(Thread owner, Connection connection) {
        Map<Connection, Throwable> held = HELD.get(owner);
        if (held != null) {
            held.remove(connection);
            if (held.isEmpty()) {
                HELD.remove(owner, held);
            }
        }
    }

    /** The frames that say where: the server's own and Spring Session's, at most 14, without this class's. */
    private static List<String> condense(Throwable stack) {
        return Arrays.stream(stack.getStackTrace())
            .filter(frame -> (frame.getClassName().startsWith("com.flowtivo.")
                    || frame.getClassName().startsWith("org.springframework.session.")
                    || frame.getClassName().startsWith("net.javacrumbs."))
                    && !frame.getClassName().startsWith(ConnectionWatch.class.getName())
                    && !frame.getClassName().startsWith(TenantAwareDataSource.class.getName())
                    && !frame.getClassName().equals("com.flowtivo.tinfirm.shared.tx.ReleasingTransactionManager"))
            .limit(14)
            .map(StackTraceElement::toString)
            .toList();
    }

    private static void writeReport() {
        StringBuilder report = new StringBuilder();
        List<Map.Entry<Nested, AtomicLong>> entries = new ArrayList<>(NESTED.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<Nested, AtomicLong> entry) -> entry.getValue().get())
            .reversed());
        report.append("acquisitions: ").append(ACQUISITIONS.get()).append('\n');
        report.append("nested acquisitions: ")
            .append(entries.stream().mapToLong(entry -> entry.getValue().get()).sum())
            .append(" at ")
            .append(entries.size())
            .append(" places\n\n");
        for (Map.Entry<Nested, AtomicLong> entry : entries) {
            report.append(entry.getValue().get()).append("x\n").append(entry.getKey()).append("\n\n");
        }
        try {
            Path file = Path.of("target", "connection-watch-" + ProcessHandle.current().pid() + ".txt");
            Files.writeString(file, report);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static final class WatchingDataSource extends DelegatingDataSource {

        WatchingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Throwable stack = asking();
            return acquired(obtainTargetDataSource().getConnection(), stack);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Throwable stack = asking();
            return acquired(obtainTargetDataSource().getConnection(username, password), stack);
        }
    }

    /**
     * Wraps the pool before {@code TenantAwareDataSource} wraps it in turn, so that the watch sees each connection the
     * pool hands out.
     */
    static final class WatchingPostProcessor implements BeanPostProcessor, PriorityOrdered {

        private static final Set<Class<?>> SKIP = Set.of(TenantAwareDataSource.class, WatchingDataSource.class);

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DataSource dataSource && !SKIP.contains(bean.getClass())
                    && !"off".equals(System.getenv("TINFIRM_TEST_CONNECTION_WATCH"))) {
                return watch(dataSource);
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return HIGHEST_PRECEDENCE;
        }
    }
}
