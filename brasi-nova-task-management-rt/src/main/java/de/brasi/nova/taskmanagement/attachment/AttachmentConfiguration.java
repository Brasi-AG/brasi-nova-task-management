package de.brasi.nova.taskmanagement.attachment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registriert {@link AttachmentProperties} und {@link AttachmentThumbnailPoolProperties} als Beans.
 * Die Klasse liegt im Paket {@code attachment}, damit auch Integrationstests, die nur dieses Paket
 * scannen, die Bindung der Anhang-Konfiguration erhalten.
 */
@Configuration
@EnableConfigurationProperties({
  AttachmentProperties.class,
  AttachmentThumbnailPoolProperties.class
})
public class AttachmentConfiguration {}
