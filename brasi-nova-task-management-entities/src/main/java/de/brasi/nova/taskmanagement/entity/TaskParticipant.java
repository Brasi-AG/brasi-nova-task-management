package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Teilnehmer einer Aufgabe (Ersteller, Assignee, Beobachter). */
@NovaTable(name = "TASK_PARTICIPANT")
@Getter
@Setter
@NoArgsConstructor
public class TaskParticipant extends NovaEntity<TaskParticipant> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf die Aufgabe. */
  @NovaColumn(name = "TASK_ID", nullable = false)
  private Long taskId;

  /** UUID des Teilnehmers (Person oder Team). */
  @NovaColumn(name = "PARTICIPANT_REF", nullable = false)
  private UUID participantRef;

  /** Art des Teilnehmers (PERSON | TEAM). Von KSF nativ als String persistiert. */
  @NovaColumn(name = "PARTICIPANT_KIND", nullable = false)
  private ParticipantKind participantKind;

  /** Mandant des Teilnehmers. */
  @NovaColumn(name = "PARTICIPANT_TENANT_ID", nullable = false)
  private Long participantTenantId;

  /** Kennzeichen: Teilnehmer ist der Ersteller. */
  @NovaColumn(name = "CREATOR_FLAG", nullable = false)
  private boolean creatorFlag;

  /** Kennzeichen: Teilnehmer ist Assignee. */
  @NovaColumn(name = "ASSIGNEE_FLAG", nullable = false)
  private boolean assigneeFlag;

  /** Kennzeichen: allgemeiner Teilnehmer. */
  @NovaColumn(name = "PARTICIPANT_FLAG", nullable = false)
  private boolean participantFlag;

  /** Optionale Austrittsart (HANDOVER | UNSUBSCRIBED). */
  @NovaColumn(name = "EXIT_KIND", nullable = true)
  private ExitKind exitKind;

  /** Optionaler Zeitpunkt des Austritts. */
  @NovaColumn(name = "EXIT_AT", nullable = true)
  private LocalDateTime exitAt;
}
