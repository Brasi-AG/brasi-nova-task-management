package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.brasi.nova.taskmanagement.inbox.model.ArchiveTotals;
import de.brasi.nova.taskmanagement.inbox.model.ExitModel;
import de.brasi.nova.taskmanagement.inbox.model.InboxSnapshotModel;
import de.brasi.nova.taskmanagement.inbox.model.TaskSummaryModel;
import de.brasi.nova.taskmanagement.notification.model.NotificationPage;
import de.brasi.nova.taskmanagement.paging.KeysetPage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2.5-Vertragstest (BE-1): fixiert die JSON-/Wire-Form der additiv erweiterten Lese-Modelle VOR der
 * Service-Verdrahtung. Insbesondere die FE-erwarteten Schluesselnamen der booleschen Felder
 * ({@code isPinned/isFlagged/isMuted/isDone}), {@code createdAt} als ISO-8601-Instant,
 * {@code exit{kind}} und {@code currentUserId} im Snapshot.
 *
 * <p>Die Status-{@code ->}{@code isDone}-Ableitung (DONE/CANCELLED) und die asOf-gebundene
 * {@code exit}-Ableitung sind in {@link InboxEnrichmentAssemblerTest} (exit) bzw.
 * {@code InboxSnapshotServiceTest} (isDone-Mapping) verankert; hier wird nur die serialisierte Form
 * festgezurrt.
 */
class InboxReadContractTest {

  private final ObjectMapper om =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID CREATOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID PARTICIPANT = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID TAG = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final UUID CURRENT_USER = UUID.fromString("99999999-9999-9999-9999-999999999999");

  @Test
  @DisplayName("TaskSummaryModel JSON-Form: alle additiven Felder + FE-Schluesselnamen (is-Praefix)")
  void task_summary_wire_form() throws Exception {
    final TaskSummaryModel m =
        new TaskSummaryModel(
            ID,
            "Titel",
            "OPEN",
            "NORMAL",
            LocalDate.of(2026, 12, 31),
            Instant.parse("2026-10-18T08:00:00Z"),
            3,
            CREATOR,
            Instant.parse("2026-10-01T06:30:00Z"),
            "Beschreibung",
            List.of(ASSIGNEE),
            List.of(PARTICIPANT),
            List.of(TAG),
            true,
            false,
            true,
            false,
            null);

    final JsonNode j = om.readTree(om.writeValueAsString(m));

    // bestehende 7 Felder bleiben (rueckwaerts-kompatibel)
    assertThat(j.get("id").asText()).isEqualTo(ID.toString());
    assertThat(j.get("title").asText()).isEqualTo("Titel");
    assertThat(j.get("statusCode").asText()).isEqualTo("OPEN");
    assertThat(j.get("priorityCode").asText()).isEqualTo("NORMAL");
    assertThat(j.get("dueDate").asText()).isEqualTo("2026-12-31");
    assertThat(j.get("lastActivityAt").asText()).isEqualTo("2026-10-18T08:00:00Z");
    assertThat(j.get("commentCount").asInt()).isEqualTo(3);

    // additive Felder
    assertThat(j.get("createdById").asText()).isEqualTo(CREATOR.toString());
    assertThat(j.get("createdAt").asText()).isEqualTo("2026-10-01T06:30:00Z");
    assertThat(j.get("description").asText()).isEqualTo("Beschreibung");
    assertThat(j.get("assigneeIds")).isNotNull();
    assertThat(j.get("assigneeIds").isArray()).isTrue();
    assertThat(j.get("assigneeIds").get(0).asText()).isEqualTo(ASSIGNEE.toString());
    assertThat(j.get("participantIds").get(0).asText()).isEqualTo(PARTICIPANT.toString());
    assertThat(j.get("tagIds").get(0).asText()).isEqualTo(TAG.toString());

    // FE-Schluesselnamen der booleschen Felder MIT is-Praefix (nicht "pinned"/"done")
    assertThat(j.has("isPinned")).as("JSON-Key muss 'isPinned' heissen").isTrue();
    assertThat(j.has("isFlagged")).isTrue();
    assertThat(j.has("isMuted")).isTrue();
    assertThat(j.has("isDone")).as("JSON-Key muss 'isDone' heissen").isTrue();
    assertThat(j.has("pinned")).as("kein bean-gestripptes 'pinned'").isFalse();
    assertThat(j.has("done")).as("kein bean-gestripptes 'done'").isFalse();
    assertThat(j.get("isPinned").asBoolean()).isTrue();
    assertThat(j.get("isFlagged").asBoolean()).isFalse();
    assertThat(j.get("isMuted").asBoolean()).isTrue();
    assertThat(j.get("isDone").asBoolean()).isFalse();

    // exit = null bei aktivem Member
    assertThat(j.get("exit").isNull()).as("exit=null bei aktivem Member").isTrue();
  }

  @Test
  @DisplayName("exit{kind} serialisiert als Objekt mit kind")
  void exit_model_wire_form() throws Exception {
    final TaskSummaryModel m =
        new TaskSummaryModel(
            ID, "T", "DONE", "NORMAL", null, Instant.parse("2026-10-18T08:00:00Z"), 0,
            null, Instant.parse("2026-10-01T06:30:00Z"), null,
            List.of(), List.of(), List.of(), false, false, false, true,
            new ExitModel(InboxEnrichmentAssembler.EXIT_HANDOVER, List.of()));

    final JsonNode j = om.readTree(om.writeValueAsString(m));
    assertThat(j.get("exit").isObject()).isTrue();
    assertThat(j.get("exit").get("kind").asText()).isEqualTo("handover");
    // BE-7: handedOverToIds ist Teil der Wire-Form (hier ohne Ziele -> leeres Array).
    assertThat(j.get("exit").get("handedOverToIds").isArray()).isTrue();
    assertThat(j.get("exit").get("handedOverToIds")).isEmpty();
    // isDone fuer DONE true (Wire-Form; Mapping aus statusCode im Service verankert)
    assertThat(j.get("isDone").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("InboxSnapshotModel JSON-Form: currentUserId vorhanden")
  void snapshot_wire_form_current_user_id() throws Exception {
    final InboxSnapshotModel snap =
        new InboxSnapshotModel(
            Instant.parse("2026-10-18T08:00:00Z"),
            CURRENT_USER,
            new KeysetPage<>(List.of(), null, false, Instant.parse("2026-10-18T08:00:00Z")),
            new NotificationPage(List.of(), null, false),
            List.of(),
            List.of(),
            new ArchiveTotals(0, 0, 0));

    final JsonNode j = om.readTree(om.writeValueAsString(snap));
    assertThat(j.has("currentUserId")).as("Snapshot muss currentUserId tragen").isTrue();
    assertThat(j.get("currentUserId").asText()).isEqualTo(CURRENT_USER.toString());
    assertThat(j.get("asOf").asText()).isEqualTo("2026-10-18T08:00:00Z");
  }
}
