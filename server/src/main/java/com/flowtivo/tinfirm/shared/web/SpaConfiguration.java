package com.flowtivo.tinfirm.shared.web;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.EncodedResourceResolver;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Angular SPA from {@code classpath:/static/} (copied there by the Docker build).
 * A GET that matches no file returns {@code index.html}, so client-side routes work on reload, except under
 * {@code /api}, {@code /oauth2}, {@code /login/oauth2}, {@code /actuator}, {@code /.well-known} and {@code /scim}, which
 * answer 404.
 * <p>
 * Build files whose names carry a content hash are cached for a year by browsers and any CDN in front: a new
 * build gives them new names. A missing one answers 404 instead of {@code index.html}, so a tab still running
 * an older build sees its lazy chunk fail (the SPA then loads the page again) instead of getting HTML as
 * JavaScript. Everything else, {@code index.html} included, keeps Spring Security's no-store default.
 * <p>
 * The Docker build gzips each hashed JavaScript and CSS file once, beside it ({@code chunk-5DTZP5O2.js.gz}). A
 * browser that accepts gzip gets that copy, with {@code Content-Encoding: gzip} and {@code Vary: Accept-Encoding};
 * nothing is compressed per request. Only these files: they hold no secret, so their size tells an attacker
 * nothing (BREACH), and the API's answers and event streams go out as they are.
 * <p>
 * The hashed files and {@code /media/**} ({@link #BUILD_FILES}) are public, so the security configuration serves
 * them without the session: a page's first load fetches every lazy chunk (the router preloads them all).
 */
@Configuration(proxyBeanMethods = false)
public class SpaConfiguration implements WebMvcConfigurer {

    /** Angular's build output with {@code outputHashing: all}, e.g. {@code chunk-5DTZP5O2.js}. */
    static final String[] HASHED_FILES = { "/main-*.js", "/polyfills-*.js", "/chunk-*.js", "/styles-*.css" };

    /** Fonts and images referenced from the stylesheet, hashed too. */
    static final String MEDIA = "/media/**";

    /** Every file of the build whose name carries a content hash: public and immutable. */
    public static final List<String> BUILD_FILES = Stream.concat(Stream.of(HASHED_FILES), Stream.of(MEDIA)).toList();

    static final CacheControl HASHED_FILE_CACHING = CacheControl.maxAge(Duration.ofDays(365))
        .cachePublic()
        .immutable();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(HASHED_FILES)
            .addResourceLocations("classpath:/static/")
            .setCacheControl(HASHED_FILE_CACHING)
            .resourceChain(false)
            .addResolver(gzipCopies())
            .addResolver(new PathResourceResolver());
        // Fonts and images referenced from the stylesheet, hashed too. A `/**` pattern resolves the part after
        // the prefix, hence the location of its own.
        registry.addResourceHandler(MEDIA)
            .addResourceLocations("classpath:/static/media/")
            .setCacheControl(HASHED_FILE_CACHING)
            .resourceChain(false);
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            // No resource cache: the fallback would otherwise cache an entry per arbitrary URL.
            .resourceChain(false)
            .addResolver(new SpaResourceResolver());
    }

    /** The build's {@code .gz} copy of a file, when the browser accepts gzip and the copy exists. */
    static EncodedResourceResolver gzipCopies() {
        EncodedResourceResolver resolver = new EncodedResourceResolver();
        resolver.setContentCodings(List.of("gzip"));
        return resolver;
    }

    static final class SpaResourceResolver extends PathResourceResolver {

        private static final List<String> NON_SPA_PREFIXES = List.of("api", "oauth2", "login/oauth2", "actuator",
                ".well-known", "scim");

        @Override
        protected @Nullable Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = super.getResource(resourcePath, location);
            if (resource != null || isNonSpaPath(resourcePath)) {
                return resource;
            }
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }

        static boolean isNonSpaPath(String resourcePath) {
            for (String prefix : NON_SPA_PREFIXES) {
                if (resourcePath.equals(prefix) || resourcePath.startsWith(prefix + "/")) {
                    return true;
                }
            }
            return false;
        }
    }
}
