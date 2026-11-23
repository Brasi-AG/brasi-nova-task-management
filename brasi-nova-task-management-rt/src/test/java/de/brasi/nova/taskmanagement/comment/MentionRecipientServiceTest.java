package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.division.DivisionMentionResolver;
import de.brasi.nova.taskmanagement.participant.AssigneeService;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit-Tests fuer {@link MentionRecipientService} (Intake #190, Task 10; gemockt, kein
 * DB-Zugriff).
 *
 * <p>Der Dienst ist die aus {@code CommentService#createComment} herausgeloeste Ermittlung der
 * Erwaehnungs-Empfaenger und wird vom Kommentar und vom PATCH der Beschreibung geteilt: Abteilungen
 * ueber den {@link DivisionMentionResolver} aufloesen, mit den Personen vereinigen und
 * deduplizieren, auf aktive Benutzer des Mandanten filtern ({@code findActiveByUuids}), Akteur und
 * {@code excludedRecipient} ausnehmen, WRITE-Mitglieder als Ziel, Nicht-Mitglieder nur mit dem
 * Recht von {@code POST /participants} ueber {@code addAsParticipants} aufnehmen (sonst still
 * verworfen).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MentionRecipientServiceTest {

  private static final long TENANT_ID = 590L;
  private static final Long TASK_ID = 5901L;
  private static final UUID ACTOR_REF = UUID.randomUUID();
  private static final UUID EXCLUDED_REF = UUID.randomUUID();
  private static final UUID MEMBER_REF = UUID.randomUUID();
  private static final UUID NON_MEMBER_REF = UUID.randomUUID();
  private static final UUID DIVISION_REF = UUID.randomUUID();

  @Mock private UserReadRepository userReadRepository;
  @Mock private TaskMembershipGuard membershipGuard;
  @Mock private AssigneeService assigneeService;
  @Mock private DivisionMentionResolver divisionMentionResolver;

  private MentionRecipientService service;

  @BeforeEach
  void setUp() {
    service =
        new MentionRecipientService(
            userReadRepository, membershipGuard, assigneeService, divisionMentionResolver);
    // Default: Akteur, Ausgenommener und Mitglied sind WRITE-Mitglieder, das Nicht-Mitglied nicht.
    when(membershipGuard.isWriteMember(eq(TASK_ID), any(), eq(TENANT_ID))).thenReturn(true);
    when(membershipGuard.isWriteMember(TASK_ID, NON_MEMBER_REF, TENANT_ID)).thenReturn(false);
    when(divisionMentionResolver.resolve(any())).thenReturn(Set.of());
  }

  @Test
  @DisplayName(
      "i190_excluded_recipient_and_actor_not_targeted: Akteur und excludedRecipient (aus mentions"
          + " und aus der Abteilung) sind weder Ziel noch werden sie aufgenommen; Mitglied ist Ziel,"
          + " Nicht-Mitglied wird mit Recht aufgenommen und ist Ziel")
  void i190_excluded_recipient_and_actor_not_targeted() {
    when(divisionMentionResolver.resolve(any()))
        .thenReturn(new LinkedHashSet<>(List.of(ACTOR_REF, EXCLUDED_REF, NON_MEMBER_REF)));
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(
            List.of(
                userWith(ACTOR_REF),
                userWith(EXCLUDED_REF),
                userWith(MEMBER_REF),
                userWith(NON_MEMBER_REF)));
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(TASK_ID), eq(TENANT_ID), any()))
        .thenReturn(List.of(NON_MEMBER_REF));

    final MentionRecipients result =
        service.collect(
            TASK_ID,
            TENANT_ID,
            ACTOR_REF,
            EXCLUDED_REF,
            List.of(ACTOR_REF, EXCLUDED_REF, MEMBER_REF),
            List.of(DIVISION_REF));

    assertThat(result.notifyTargets())
        .as("kein Selbst-Ping, kein Ziel fuer den Ausgenommenen")
        .containsExactlyInAnyOrder(MEMBER_REF, NON_MEMBER_REF);
    assertThat(result.admitted()).containsExactly(NON_MEMBER_REF);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> added = ArgumentCaptor.forClass(Collection.class);
    verify(assigneeService).addAsParticipants(eq(TASK_ID), eq(TENANT_ID), added.capture());
    assertThat(added.getValue())
        .as("nur das Nicht-Mitglied wird aufgenommen")
        .containsExactly(NON_MEMBER_REF);
  }

  @Test
  @DisplayName(
      "i190_non_members_dropped_without_task_write: ohne mayAddParticipants wird niemand"
          + " aufgenommen, das Nicht-Mitglied faellt still weg, das Mitglied bleibt Ziel")
  void i190_non_members_dropped_without_task_write() {
    when(divisionMentionResolver.resolve(any()))
        .thenReturn(new LinkedHashSet<>(List.of(NON_MEMBER_REF)));
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(MEMBER_REF), userWith(NON_MEMBER_REF)));
    when(assigneeService.mayAddParticipants()).thenReturn(false);

    final MentionRecipients result =
        service.collect(
            TASK_ID, TENANT_ID, ACTOR_REF, null, List.of(MEMBER_REF), List.of(DIVISION_REF));

    assertThat(result.notifyTargets()).containsExactly(MEMBER_REF);
    assertThat(result.admitted()).isEmpty();
    verify(assigneeService, never()).addAsParticipants(anyLong(), anyLong(), any());
  }

  @Test
  @DisplayName(
      "i190_persons_and_divisions_deduplicated_before_user_lookup: Personen aus mentions und"
          + " Abteilung werden vereinigt; findActiveByUuids sieht jede UUID genau einmal")
  void i190_persons_and_divisions_deduplicated_before_user_lookup() {
    when(divisionMentionResolver.resolve(any()))
        .thenReturn(new LinkedHashSet<>(List.of(MEMBER_REF, NON_MEMBER_REF)));
    when(userReadRepository.findActiveByUuids(any())).thenReturn(List.of(userWith(MEMBER_REF)));

    final MentionRecipients result =
        service.collect(
            TASK_ID,
            TENANT_ID,
            ACTOR_REF,
            null,
            List.of(MEMBER_REF, MEMBER_REF),
            List.of(DIVISION_REF));

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> lookup = ArgumentCaptor.forClass(Collection.class);
    verify(userReadRepository).findActiveByUuids(lookup.capture());
    assertThat(lookup.getValue()).containsExactlyInAnyOrder(MEMBER_REF, NON_MEMBER_REF);
    assertThat(result.notifyTargets())
        .as("nicht aufgeloeste UUID (kein aktiver Benutzer) faellt still weg")
        .containsExactly(MEMBER_REF);
    assertThat(result.admitted()).isEmpty();
  }

  @Test
  @DisplayName(
      "i190_unknown_division_400_before_user_lookup_and_admission: der 400 des Resolvers kommt"
          + " vor findActiveByUuids und vor jeder Aufnahme")
  void i190_unknown_division_400_before_user_lookup_and_admission() {
    when(divisionMentionResolver.resolve(any()))
        .thenThrow(new EntityValidationException("Unbekannte oder nicht aktive Abteilung."));
    when(assigneeService.mayAddParticipants()).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.collect(
                    TASK_ID,
                    TENANT_ID,
                    ACTOR_REF,
                    null,
                    List.of(NON_MEMBER_REF),
                    List.of(DIVISION_REF)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("Unbekannte oder nicht aktive Abteilung.");

    verify(userReadRepository, never()).findActiveByUuids(any());
    verify(assigneeService, never()).addAsParticipants(anyLong(), anyLong(), any());
  }

  @Test
  @DisplayName(
      "i190_empty_input_reads_no_users: ohne Personen und Abteilungen keine Benutzerlesung, keine"
          + " Aufnahme, leeres Ergebnis")
  void i190_empty_input_reads_no_users() {
    final MentionRecipients result =
        service.collect(TASK_ID, TENANT_ID, ACTOR_REF, null, List.of(), List.of());

    assertThat(result.notifyTargets()).isEmpty();
    assertThat(result.admitted()).isEmpty();
    verify(userReadRepository, never()).findActiveByUuids(any());
    verify(assigneeService, never()).addAsParticipants(anyLong(), anyLong(), any());
  }

  @Test
  @DisplayName(
      "i190_concurrently_admitted_person_still_targeted: meldet addAsParticipants eine Person"
          + " nicht (gleichzeitig aufgenommen), ist sie danach WRITE-Mitglied und wird trotzdem"
          + " Ziel und als aufgenommen gefuehrt (Verhalten wie bisher im Kommentar)")
  void i190_concurrently_admitted_person_still_targeted() {
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(NON_MEMBER_REF)));
    when(membershipGuard.isWriteMember(TASK_ID, NON_MEMBER_REF, TENANT_ID))
        .thenReturn(false)
        .thenReturn(true);
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(TASK_ID), eq(TENANT_ID), any()))
        .thenReturn(List.of());

    final MentionRecipients result =
        service.collect(TASK_ID, TENANT_ID, ACTOR_REF, null, List.of(NON_MEMBER_REF), List.of());

    assertThat(result.notifyTargets()).containsExactly(NON_MEMBER_REF);
    assertThat(result.admitted()).containsExactly(NON_MEMBER_REF);
  }

  @Test
  @DisplayName(
      "i190_reads_before_admission: Abteilungsaufloesung, Benutzerlesung und"
          + " Mitgliedschaftspruefung laufen vor addAsParticipants (erste Schreibung)")
  void i190_reads_before_admission() {
    when(divisionMentionResolver.resolve(any()))
        .thenReturn(new LinkedHashSet<>(List.of(NON_MEMBER_REF)));
    when(userReadRepository.findActiveByUuids(any()))
        .thenReturn(List.of(userWith(NON_MEMBER_REF)));
    when(assigneeService.mayAddParticipants()).thenReturn(true);
    when(assigneeService.addAsParticipants(eq(TASK_ID), eq(TENANT_ID), any()))
        .thenReturn(List.of(NON_MEMBER_REF));

    service.collect(TASK_ID, TENANT_ID, ACTOR_REF, null, List.of(), List.of(DIVISION_REF));

    final InOrder order =
        inOrder(divisionMentionResolver, userReadRepository, membershipGuard, assigneeService);
    order.verify(divisionMentionResolver).resolve(any());
    order.verify(userReadRepository).findActiveByUuids(any());
    order.verify(membershipGuard).isWriteMember(TASK_ID, NON_MEMBER_REF, TENANT_ID);
    order.verify(assigneeService).addAsParticipants(eq(TASK_ID), eq(TENANT_ID), any());
  }

  private static User userWith(final UUID uuid) {
    final User u = new User();
    u.setId(1L);
    u.setUuid(uuid);
    u.setLoginName("test");
    return u;
  }
}
