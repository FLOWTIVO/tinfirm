package com.flowtivo.tinfirm.shared.web;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * No flash attributes: nothing here redirects with them (the SPA and the API answer JSON). Spring MVC's default,
 * {@code SessionFlashMapManager}, looks for them in the session on every request the {@link DispatcherServlet}
 * handles, the web app's files included. With the JDBC sessions, that read loaded the session from the database and
 * made Spring Session write its last access back: two trips to the database for each file of the build.
 */
@Configuration(proxyBeanMethods = false)
class NoFlashMapsConfiguration {

    @Bean(name = DispatcherServlet.FLASH_MAP_MANAGER_BEAN_NAME)
    FlashMapManager flashMapManager() {
        return new FlashMapManager() {
            @Override
            public @Nullable FlashMap retrieveAndUpdate(HttpServletRequest request, HttpServletResponse response) {
                return null;
            }

            @Override
            public void saveOutputFlashMap(FlashMap flashMap, HttpServletRequest request,
                    HttpServletResponse response) {
                // Never asked for: no handler adds flash attributes.
            }
        };
    }
}
