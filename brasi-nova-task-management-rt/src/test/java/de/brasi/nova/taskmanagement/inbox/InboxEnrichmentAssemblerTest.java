package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.repository.TaskTagUuidRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2-Unit-Test (BE-1): der {@link InboxEnrichmentAssembler} gruppiert die Batch-Rows je Aufgabe und
 * leitet Ersteller/Assignees/Teilnehmer, Tags, Marker und den asOf-gebundenen {@code exitKind} rein
 * funktional ab - ohne DB.
 */
class InboxEnrichmentAssemblerTest {

  private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
  private static final LocalDateTime BEFORE = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
  private static final LocalDateTime AFTER = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

  private static final long TASK = 1L;
  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID USER_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
  private static final UUID TEAM_X = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
  private static final UUID TAG_1 = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final UUID TAG_2 = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

  private final InboxEnrichmentAssembler assembler = new InboxEnrichmentAssembler();

  @Test
  @DisplayName("gruppiert creator/assignees/participants PERSON-only, participant minus assignee/creator")
  void gruppiert_rollen_korrekt() {
    final List<TaskParticipant> ps =
        List.of(
            participant(ACTOR, ParticipantKind.PERSON, true, false, false, null, null, BEFORE),
            participant(USER_B, ParticipantKind.PERSON, false, true, true, null, null, BEFORE),
            participant(USER_C, ParticipantKind.PERSON, false, false, true, null, null, BEFORE),
            // TEAM-Assignee -> in BE-1 NICHT in den PERSON-Anzeigelisten (BE-T)
            participant(TEAM_X, ParticipantKind.TEAM, false, true, false, null, null, BEFORE));

    final Map<Long, TaskEnrichment> out =
        assembler.assemble(List.of(TASK), ps, List.of(), List.of(), ACTOR, AS_OF);

    final TaskEnrichment e = out.get(TASK);
    assertThat(e.createdById()).isEqualTo(ACTOR);
    // B ist Assignee (+Teilnehmer-Flag), erscheint nur als Assignee; C ist reiner Teilnehmer
    assertThat(e.assigneeIds()).containsExactly(USER_B);
    assertThat(e.participantIds()).containsExactly(USER_C);
    assertThat(e.exitKind()).isNull();
  }

  @Test
  @DisplayName("ausgetretene Rows (EXIT_KIND) zaehlen nicht zu Assignee-/Teilnehmer-Anzeigelisten")
  void ausgetretene_rows_nicht_in_anzeigelisten() {
    final List<TaskParticipant> ps =
        List.of(
            participant(ACTOR, ParticipantKind.PERSON, true, false, false, null, null, BEFORE),
            participant(USER_B, ParticipantKind.PERSON, false, true, false, ExitKind.HANDOVER, BEFORE, BEFORE),
            participant(USER_C, ParticipantKind.PERSON, false, false, true, ExitKind.UNSUBSCRIBED, BEFORE, BEFORE));

    final Map<Long, TaskEnrichment> out =
        assembler.assemble(List.of(TASK), ps, List.of(), List.of(), ACTOR, AS_OF);

    final TaskEnrichment e = out.get(TASK);
    assertThat(e.assigneeIds()).isEmpty();
    assertThat(e.participantIds()).isEmpty();
  }

  @Test
  @DisplayName("Tags je Aufgabe aus der Projektion gruppiert (inkl. mehrere Aufgaben)")
  void tags_je_aufgabe_gruppiert() {
    final List<TaskTagUuidRow> tagRows =
        List.of(new TaskTagUuidRow(1L, TAG_1), new TaskTagUuidRow(1L, TAG_2), new TaskTagUuidRow(2L, TAG_1));

    final Map<Long, TaskEnrichment> out =
        assembler.assemble(List.of(1L, 2L, 3L), List.of(), tagRows, List.of(), ACTOR, AS_OF);

    assertThat(out.get(1L).tagIds()).containsExactlyInAnyOrder(TAG_1, TAG_2);
    assertThat(out.get(2L).tagIds()).containsExactly(TAG_1);
    assertThat(out.get(3L).tagIds()).isEmpty();
  }

  @Test
  @DisplayName("Marker aus Actor-UserState; fehlende UserState -> alle false")
  void marker_aus_userstate() {
    final TaskUserState st = userState(ACTOR, true, false, true, null, BEFORE);

    final Map<Long, TaskEnrichment> out =
        assembler.assemble(List.of(1L, 2L), List.of(), List.of(), List.of(withTask(st, 1L)), ACTOR, AS_OF);

    assertThat(out.get(1L).isPinned()).isTrue();
    assertThat(out.get(1L).isFlagged()).isFalse();
    assertThat(out.get(1L).isMuted()).isTrue();
    // Task 2 ohne UserState -> alle Marker false
    assertThat(out.get(2L).isPinned()).isFalse();
    assertThat(out.get(2L).isFlagged()).isFalse();
    assertThat(out.get(2L).isMuted()).isFalse();
  }

  @Test
  @DisplayName("exit=handover: Actor-Participant EXIT_KIND=HANDOVER, createdAt<=asOf, exitAt<=asOf")
  void exit_handover() {
    final List<TaskParticipant> ps =
        List.of(participant(ACTOR, ParticipantKind.PERSON, false, false, true, ExitKind.HANDOVER, BEFORE, BEFORE));
    final TaskEnrichment e =
        assembler.assemble(List.of(TASK), ps, List.of(), List.of(), ACTOR, AS_OF).get(TASK);
    assertThat(e.exitKind()).isEqualTo(InboxEnrichmentAssembler.EXIT_HANDOVER);
  }

  @Test
  @DisplayName("exit=handover auch bei EXIT_AT=null, solange createdAt<=asOf")
  void exit_handover_null_exit_at() {
    final List<TaskParticipant> ps =
        List.of(participant(ACTOR, ParticipantKind.PERSON, false, false, true, ExitKind.HANDOVER, null, BEFORE));
    final TaskEnrichment e =
        assembler.assemble(List.of(TASK), ps, List.of(), List.of(), ACTOR, AS_OF).get(TASK);
    assertThat(e.exitKind()).isEqualTo(InboxEnrichmentAssembler.EXIT_HANDOVER);
  }

  @Test
  @DisplayName("exit=unsubscribed via Participant EXIT_KIND=UNSUBSCRIBED")
  void exit_unsubscribed_participant() {
    final List<TaskParticipant> ps =
        List.of(participant(ACTOR, ParticipantKind.PERSON, false, false, true, ExitKind.UNSUBSCRIBED, BEFORE, BEFORE));
    final TaskEnrichment e =
        assembler.assemble(List.of(TASK), ps, List.of(), List.of(), ACTOR, AS_OF).get(TASK);
    assertThat(e.exitKind()).isEqualTo(InboxEnrichmentAssembler.EXIT_UNSUBSCRIBED);
  }

  @Test
  @DisplayName("exit=unsubscribed via UserState UNSUBSCRIBED_AT<=asOf und ANGELEGT_AM<=asOf")
  void exit_unsubscribed_userstate() {
    final TaskUserState st = withTask(userState(ACTOR, false, false, false, BEFORE, BEFORE), TASK);
    final TaskEnrichment e =
        assembler.assemble(List.of(TASK), List.of(), List.of(), List.of(st), ACTOR, AS_OF).get(TASK);
    assertThat(e.exitKind()).isEqualTo(InboxEnrichmentAssembler.EXIT_UNSUBSCRIBED);
  }

  @Test
  @DisplayName("Praezedenz: HANDOVER vor UNSUBSCRIBED bei gleichzeitigen Signalen")
  void exit_praezedenz_handover_vor_unsubscribed() {
    final List<TaskParticipant> ps =
        List.of(participant(ACTOR, ParticipantKind.PERSON, false, false, true, ExitKind.HANDOVER, BEFORE, BEFORE));
    final TaskUserState st = withTask(userState(ACTOR, false, false, false, BEFORE, BEFORE), TASK);
    final TaskEnrichment e =
        assembler.assemble(List.of(TASK), ps, List.of(), List.of(st), ACTOR, AS_OF).get(TASK);
    assertThat(e.exitKind()).isEqualTo(InboxEnrichmentAssembler.EXIT_HANDOVER);
  }

  @Test
  @DisplayName("asOf-Grenzen: Signale NACH asOf ergeben exit=null")
  void exit_null_wenn_signale_nach_asof() {
    // EXIT_AT nach asOf -> kein Handover
    final List<TaskParticipant> psExitAfter =
        List.of(participant(ACTOR, ParticipantKind.PERSON, false, false, true, ExitKind.HANDOVER, AFTER, BEFORE));
    assertThat(assembler.assemble(List.of(TASK), psExitAfter, List.of(), List.of(), ACTOR, AS_OF).get(TASK).exitKind())
        .isNull();

    // ANGELEGT_AM nach asOf -> Mitgliedschaft existierte beim Wasserzeichen noch nicht
    final List<TaskParticipant> psJoinAfter =
        List.of(participant(ACTOR, ParticipantKind.PERSON, false, false, true, ExitKind.HANDOVER, null, AFTER));
    assertThat(assembler.assemble(List.of(TASK), psJoinAfter, List.of(), List.of(), ACTOR, AS_OF).get(TASK).exitKind())
        .isNull();

    // UNSUBSCRIBED_AT nach asOf -> noch nicht abgemeldet
    final TaskUserState stUnsubAfter = withTask(userState(ACTOR, false, false, false, AFTER, BEFORE), TASK);
    assertThat(assembler.assemble(List.of(TASK), List.of(), List.of(), List.of(stUnsubAfter), ACTOR, AS_OF).get(TASK).exitKind())
        .isNull();
  }

  @Test
  @DisplayName("Actor-Exit NICHT auf PERSON eingeschraenkt (deckungsgleich mit left-SQL)")
  void actor_exit_nicht_person_beschraenkt() {
    final List<TaskParticipant> ps =
        List.of(participant(ACTOR, ParticipantKind.TEAM, false, false, true, ExitKind.HANDOVER, BEFORE, BEFORE));
    final TaskEnrichment e =
        assembler.assemble(List.of(TASK), ps, List.of(), List.of(), ACTOR, AS_OF).get(TASK);
    assertThat(e.exitKind()).isEqualTo(InboxEnrichmentAssembler.EXIT_HANDOVER);
  }

  @Test
  @DisplayName("participantIds schliesst Assignees UND Ersteller aus (auch bei zusaetzlichem PARTICIPANT_FLAG)")
  void participant_minus_assignee_und_creator() {
    final List<TaskParticipant> ps =
        List.of(
            // Ersteller mit zusaetzlichem Teilnehmer-Flag
            participant(ACTOR, ParticipantKind.PERSON, true, false, true, null, null, BEFORE),
            // Assignee mit zusaetzlichem Teilnehmer-Flag
            participant(USER_B, ParticipantKind.PERSON, false, true, true, null, null, BEFORE),
            // reiner Teilnehmer
            participant(USER_C, ParticipantKind.PERSON, false, false, true, null, null, BEFORE));

    final TaskEnrichment e =
        assembler.assemble(List.of(TASK), ps, List.of(), List.of(), ACTOR, AS_OF).get(TASK);
    assertThat(e.createdById()).isEqualTo(ACTOR);
    assertThat(e.assigneeIds()).containsExactly(USER_B);
    assertThat(e.participantIds()).containsExactly(USER_C);
  }

  @Test
  @DisplayName("leere Seite / Aufgabe ohne Rows -> EMPTY-Anreicherung")
  void leere_aufgabe_empty() {
    final TaskEnrichment e =
        assembler.assemble(List.of(TASK), List.of(), List.of(), List.of(), ACTOR, AS_OF).get(TASK);
    assertThat(e.createdById()).isNull();
    assertThat(e.assigneeIds()).isEmpty();
    assertThat(e.participantIds()).isEmpty();
    assertThat(e.tagIds()).isEmpty();
    assertThat(e.isPinned()).isFalse();
    assertThat(e.exitKind()).isNull();
  }

  // ---- Hilfs-Builder -------------------------------------------------------------------------

  private static TaskParticipant participant(
      final UUID ref,
      final ParticipantKind kind,
      final boolean creator,
      final boolean assignee,
      final boolean participant,
      final ExitKind exitKind,
      final LocalDateTime exitAt,
      final LocalDateTime createdAt) {
    final TaskParticipant p = new TaskParticipant();
    p.setTaskId(TASK);
    p.setParticipantRef(ref);
    p.setParticipantKind(kind);
    p.setCreatorFlag(creator);
    p.setAssigneeFlag(assignee);
    p.setParticipantFlag(participant);
    p.setExitKind(exitKind);
    p.setExitAt(exitAt);
    p.setCreatedAt(createdAt);
    return p;
  }

  private static TaskUserState userState(
      final UUID userRef,
      final boolean pinned,
      final boolean flagged,
      final boolean muted,
      final LocalDateTime unsubscribedAt,
      final LocalDateTime createdAt) {
    final TaskUserState s = new TaskUserState();
    s.setTaskId(TASK);
    s.setUserRef(userRef);
    s.setPinned(pinned);
    s.setFlagged(flagged);
    s.setMuted(muted);
    s.setUnsubscribedAt(unsubscribedAt);
    s.setCreatedAt(createdAt);
    return s;
  }

  private static TaskUserState withTask(final TaskUserState s, final long taskId) {
    s.setTaskId(taskId);
    return s;
  }
}
