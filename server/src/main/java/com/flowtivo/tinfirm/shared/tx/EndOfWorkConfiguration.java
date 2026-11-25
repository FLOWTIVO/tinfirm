package com.flowtivo.tinfirm.shared.tx;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.SchedulingAwareRunnable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The safety net of {@link AfterTransaction} where the application's threads end a piece of work: every request (the
 * outermost filter) and every run of a scheduled job ({@code @Scheduled}, whose fixed-delay jobs share the scheduler's
 * thread). The runner hosts' WebSocket callbacks and Slack's Socket Mode frames call {@link AfterTransaction#endOfWork}
 * where they are handled.
 * <p>
 * The scheduled jobs keep the scheduler Spring chose for them: this configuration wraps the scheduler Spring has put
 * in the registrar when it asks the configurers (its own router, {@code TaskSchedulerRouter}, which chooses lazily, per
 * job, as Spring does: a job's {@code @Scheduled(scheduler = …)}, the {@code @Primary} or only {@code TaskScheduler},
 * the one named {@code taskScheduler}, a {@code ScheduledExecutorService}, else one of its own; or the one another
 * configuration set), and only ends each run of each job. It chooses nothing and so never stops a start.
 */
@Configuration(proxyBeanMethods = false)
class EndOfWorkConfiguration implements SchedulingConfigurer {

    @Bean
    FilterRegistrationBean<EndOfRequest> endOfRequestFilter() {
        FilterRegistrationBean<EndOfRequest> registration = new FilterRegistrationBean<>(new EndOfRequest());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (registrar.getScheduler() instanceof TaskScheduler chosen && !(chosen instanceof EndOfRun)) {
            registrar.setTaskScheduler(new EndOfRun(chosen));
        }
    }

    /** Ends every request, whatever it threw. */
    static final class EndOfRequest extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            try {
                chain.doFilter(request, response);
            }
            finally {
                AfterTransaction.endOfWork("the request " + request.getMethod() + " " + request.getRequestURI());
            }
        }
    }

    /** A job's run, ended by the net; the job's qualifier and kind stay visible to the router. */
    private record EndedRun(Runnable task) implements SchedulingAwareRunnable {

        @Override
        public void run() {
            try {
                task.run();
            }
            finally {
                AfterTransaction.endOfWork("the scheduled job " + task);
            }
        }

        @Override
        public boolean isLongLived() {
            return task instanceof SchedulingAwareRunnable aware && aware.isLongLived();
        }

        @Override
        public @Nullable String getQualifier() {
            return task instanceof SchedulingAwareRunnable aware ? aware.getQualifier() : null;
        }

        @Override
        public String toString() {
            return task.toString();
        }
    }

    /** The scheduler Spring chose, each run of each job ended by the net. */
    private record EndOfRun(TaskScheduler scheduler) implements TaskScheduler {

        @Override
        public @Nullable ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return scheduler.schedule(new EndedRun(task), trigger);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return scheduler.schedule(new EndedRun(task), startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return scheduler.scheduleAtFixedRate(new EndedRun(task), startTime, period);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return scheduler.scheduleAtFixedRate(new EndedRun(task), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return scheduler.scheduleWithFixedDelay(new EndedRun(task), startTime, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return scheduler.scheduleWithFixedDelay(new EndedRun(task), delay);
        }

        @Override
        public java.time.Clock getClock() {
            return scheduler.getClock();
        }
    }
}
