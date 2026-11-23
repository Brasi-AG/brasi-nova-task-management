package de.brasi.nova.taskmanagement.division;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Verdrahtung der Abteilungs-Erwaehnung (Intake #190) fuer IT-Kontexte, die das Paket {@code
 * comment} scannen, das Paket {@code division} aber nicht: {@code CommentService} haengt am {@link
 * DivisionMentionResolver}, der {@link DivisionMentionProperties} braucht. Seit Task 10 haengt auch
 * {@code TaskCommandService} ueber den {@code MentionRecipientService} daran; ITs, die das Paket
 * {@code task}, aber nicht {@code comment} scannen, importieren beide.
 *
 * <p>Bewusst ohne {@code @Configuration}: die Klasse wird nur per {@code @Import} in den {@code
 * ItConfig} der ITs geholt und von keinem Paket-Scan erfasst. Der Wert {@code
 * task-management.division-mentions.max-persons} kommt aus {@code application.properties}.
 */
@Import(DivisionMentionResolver.class)
@EnableConfigurationProperties(DivisionMentionProperties.class)
public class DivisionMentionItWiring {}
