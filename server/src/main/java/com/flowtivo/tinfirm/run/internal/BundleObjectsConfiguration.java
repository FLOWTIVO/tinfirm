package com.flowtivo.tinfirm.run.internal;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The session bundle store that {@code tinfirm.session-bundles.store} names (ADR-0097). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionBundleProperties.class)
class BundleObjectsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BundleObjectsConfiguration.class);

    @Bean
    BundleObjects bundleObjects(SessionBundleProperties properties) {
        return switch (properties.store()) {
            case NONE -> {
                log.info("Session bundles are off (tinfirm.session-bundles.store=none): runner hosts keep their "
                        + "session homes");
                yield BundleObjects.NONE;
            }
            case FILESYSTEM -> {
                try {
                    log.info("Session bundles are kept in the directory {}", properties.directory());
                    yield new FileSystemBundleObjects(properties.directory());
                }
                catch (IOException ex) {
                    throw new UncheckedIOException("Cannot use the session bundle directory "
                            + properties.directory(), ex);
                }
            }
            case S3 -> {
                log.info("Session bundles are kept in {}", properties.s3());
                yield new S3BundleObjects(properties.s3());
            }
        };
    }
}
