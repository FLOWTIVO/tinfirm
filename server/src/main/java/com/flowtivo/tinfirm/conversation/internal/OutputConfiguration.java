package com.flowtivo.tinfirm.conversation.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The settings of the conversation module's third part: files and change requests. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ AttachmentProperties.class, ChangeRequestProperties.class })
class OutputConfiguration {
}
