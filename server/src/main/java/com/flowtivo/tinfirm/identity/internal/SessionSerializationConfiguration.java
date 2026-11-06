package com.flowtivo.tinfirm.identity.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * How Spring Session JDBC reads the attributes of a session back from {@code spring_session_attributes} (the security
 * review of 2026-10-27, L21; threat model R18): Java serialisation, as Spring Session's default, but through an
 * {@link ObjectInputFilter} that admits only the classes a session of Tinfirm holds. Without it, whoever could write
 * the runtime role's tables could put any serialised object graph there, and reading the session would run its
 * gadget chain in the server, which holds the vault's master keys and the schema owner's password.
 * <p>
 * A session holds Spring Security's context (the authentication of a local login, an OIDC login with Tinfirm's
 * {@code TinfirmOidcUser}, or a SAML sign-in), the OAuth login's authorization request, the saved
 * {@code /oauth2/authorize} request, and plain values (a SAML session's end): Tinfirm's classes, Spring Security's and
 * Spring Session's, and the JDK's. Every other class, Spring's core and beans included, is refused, and so is a graph
 * deeper, wider or larger than a session needs. A refused attribute is never instantiated: reading it fails the
 * request with a {@link SerializationFailedException}.
 * <p>
 * JSON, which Spring Session can also write through this conversion service, would need a mapping of each of those
 * classes; the filter closes the same hole without changing what the sessions hold.
 */
@Configuration(proxyBeanMethods = false)
class SessionSerializationConfiguration {

    /**
     * The classes a session's attributes may name, and the limits of their graph, as a {@code jdk.serialFilter}
     * pattern. Arrays are matched by their component type; primitives always pass.
     */
    static final String FILTER = "maxdepth=32;maxrefs=10000;maxarray=100000;maxbytes=1048576;"
            + "com.flowtivo.tinfirm.**;org.springframework.security.**;org.springframework.session.**;"
            + "java.**;javax.security.**;!*";

    /** The filter of {@link #FILTER}. */
    static final ObjectInputFilter SESSION_FILTER = ObjectInputFilter.Config.createFilter(FILTER);

    /** Spring Session JDBC takes the conversion service of this name for its attributes. */
    @Bean("springSessionConversionService")
    ConversionService springSessionConversionService() {
        GenericConversionService conversions = new GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversions.addConverter(byte[].class, Object.class, new FilteredDeserializer());
        return conversions;
    }

    /** Reads an attribute through {@link #SESSION_FILTER}, with the application's class loader. */
    static final class FilteredDeserializer implements Converter<byte[], Object> {

        /** The application's class loader, which loads Tinfirm's classes and its libraries'. */
        private final ClassLoader classLoader = FilteredDeserializer.class.getClassLoader();

        @Override
        public Object convert(byte[] source) {
            try (ObjectInputStream in = new ConfigurableObjectInputStream(new ByteArrayInputStream(source),
                    classLoader)) {
                in.setObjectInputFilter(SESSION_FILTER);
                return in.readObject();
            }
            catch (IOException | ClassNotFoundException ex) {
                throw new SerializationFailedException("A session attribute could not be read: " + ex.getMessage(),
                        ex);
            }
        }
    }
}
