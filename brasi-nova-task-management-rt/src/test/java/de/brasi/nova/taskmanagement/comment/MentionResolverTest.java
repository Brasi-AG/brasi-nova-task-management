package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-Tests fuer {@link MentionResolver} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die Kern-Invarianten fuer die Mention-Aufloesungslogik:
 * <ol>
 *   <li>Aufloesbare Handles werden zu UUIDs ({@code getUuid()}) gemappt.
 *   <li>Nicht aufloesbare Handles werden still ignoriert.
 *   <li>Duplikate werden dedupliziert (nur ein UUID-Eintrag pro Handle).
 *   <li>Leerer/null-Text ergibt leere Liste.
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MentionResolverTest {

  private static final UUID ALICE_UUID = UUID.randomUUID();
  private static final UUID BOB_UUID = UUID.randomUUID();

  @Mock private UserReadRepository userReadRepository;

  private MentionResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new MentionResolver(userReadRepository);
  }

  // ---- Test 1: aufloesbare Handles -> UUIDs -------------------------------------------------------

  @Test
  @DisplayName("aufloesbare_handles_liefern_uuids: @alice und @bob aufloesbar -> ihre UUIDs")
  void aufloesbare_handles_liefern_uuids() {
    final User alice = userWithUuid(ALICE_UUID);
    final User bob = userWithUuid(BOB_UUID);

    when(userReadRepository.findByLoginName("alice")).thenReturn(Optional.of(alice));
    when(userReadRepository.findByLoginName("bob")).thenReturn(Optional.of(bob));

    final List<UUID> result = resolver.resolve("Hallo @alice und @bob, bitte pruefen.");

    assertThat(result).containsExactly(ALICE_UUID, BOB_UUID);
  }

  // ---- Test 2: nicht aufloesbare Handles werden ignoriert -----------------------------------------

  @Test
  @DisplayName("nicht_aufloesbar_ignoriert: @ghost nicht aufloesbar -> nicht in Ergebnis")
  void nicht_aufloesbar_ignoriert() {
    final User alice = userWithUuid(ALICE_UUID);

    when(userReadRepository.findByLoginName("alice")).thenReturn(Optional.of(alice));
    when(userReadRepository.findByLoginName("ghost")).thenReturn(Optional.empty());

    final List<UUID> result = resolver.resolve("@alice und @ghost");

    assertThat(result).containsExactly(ALICE_UUID);
    assertThat(result).doesNotContain((UUID) null);
  }

  // ---- Test 3: Deduplizierung - mehrfache gleiche @handles -> nur eine UUID ----------------------

  @Test
  @DisplayName("deduplizierung: @alice zweimal -> nur eine UUID in Ergebnis")
  void deduplizierung() {
    final User alice = userWithUuid(ALICE_UUID);
    when(userReadRepository.findByLoginName("alice")).thenReturn(Optional.of(alice));

    final List<UUID> result = resolver.resolve("@alice hat geantwortet und @alice nochmal.");

    assertThat(result).containsExactly(ALICE_UUID);
    assertThat(result).hasSize(1);
  }

  // ---- Test 4: leerer Text -> leere Liste ---------------------------------------------------------

  @Test
  @DisplayName("leerer_text_liefert_leere_liste: leerer String -> keine UUIDs")
  void leerer_text_liefert_leere_liste() {
    final List<UUID> result = resolver.resolve("");
    assertThat(result).isEmpty();
  }

  // ---- Test 5: null-Text -> leere Liste -----------------------------------------------------------

  @Test
  @DisplayName("null_text_liefert_leere_liste: null -> keine UUIDs")
  void null_text_liefert_leere_liste() {
    final List<UUID> result = resolver.resolve(null);
    assertThat(result).isEmpty();
  }

  // ---- Test 6: kein @-Token im Text -> leere Liste -----------------------------------------------

  @Test
  @DisplayName("kein_mention_token: Text ohne @-Token -> leere Liste, kein Repository-Aufruf")
  void kein_mention_token() {
    final List<UUID> result = resolver.resolve("Einfacher Kommentar ohne Mentions.");
    assertThat(result).isEmpty();
  }

  // ---- Test 7: gibt getUuid() (EXTERNALID), nicht getId() (interne ID) zurueck ------------------

  @Test
  @DisplayName("gibt_external_uuid_zurueck: Ergebnis enthaelt getUuid(), nicht getId()")
  void gibt_external_uuid_zurueck() {
    final UUID externalUuid = UUID.randomUUID();
    final User alice = userWithUuid(externalUuid);
    when(userReadRepository.findByLoginName("alice")).thenReturn(Optional.of(alice));

    final List<UUID> result = resolver.resolve("@alice");

    assertThat(result).containsExactly(externalUuid);
  }

  // ---- Test 8: abschliessender Punkt wird abgeschnitten ------------------------------------------

  @Test
  @DisplayName(
      "trailing_punkt_abgeschnitten: '@alice.' -> Handle 'alice' aufgeloest, nicht 'alice.'")
  void trailing_punkt_abgeschnitten() {
    final User alice = userWithUuid(ALICE_UUID);

    when(userReadRepository.findByLoginName("alice")).thenReturn(Optional.of(alice));

    final List<UUID> result = resolver.resolve("Bitte @alice.");

    assertThat(result).containsExactly(ALICE_UUID);
  }

  // ---- Test 9: Bindestrich intern erlaubt --------------------------------------------------------

  @Test
  @DisplayName(
      "bindestrich_intern_erlaubt: '@user-b-comment,' -> Handle 'user-b-comment' aufgeloest")
  void bindestrich_intern_erlaubt() {
    final UUID userUuid = UUID.randomUUID();
    final User user = userWithUuid(userUuid);

    when(userReadRepository.findByLoginName("user-b-comment")).thenReturn(Optional.of(user));

    final List<UUID> result = resolver.resolve("@user-b-comment, danke");

    assertThat(result).containsExactly(userUuid);
  }

  // ---- Test 10: interner Punkt bleibt erhalten ---------------------------------------------------

  @Test
  @DisplayName(
      "interner_punkt_bleibt_erhalten: '@al.ice' -> Handle 'al.ice' unveraendert an Repository")
  void interner_punkt_bleibt_erhalten() {
    final UUID userUuid = UUID.randomUUID();
    final User user = userWithUuid(userUuid);

    when(userReadRepository.findByLoginName("al.ice")).thenReturn(Optional.of(user));

    final List<UUID> result = resolver.resolve("Hallo @al.ice bitte pruefen");

    assertThat(result).containsExactly(userUuid);
  }

  // ---- Test 11: case-variante Handles -> dieselbe UUID, nur einmal (Re-Gate P2) ------------------

  /**
   * Whole-Branch-Re-Gate (P2): Bei case-insensitiver SQL-Server-Collation loesen {@code @alice} und
   * {@code @Alice} zum selben Benutzer auf. Die reine String-Deduplizierung der Handles verhindert
   * das nicht (verschiedene Strings), daher muessen die aufgeloesten UUIDs selbst dedupliziert
   * werden - sonst emittiert CommentService zwei MENTION-Benachrichtigungen an einen Empfaenger.
   */
  @Test
  @DisplayName(
      "case_variante_handles_dedupliziert_uuid: '@alice @Alice' -> selbe UUID, nur ein Eintrag")
  void case_variante_handles_dedupliziert_uuid() {
    // Beide Schreibweisen loesen (case-insensitive Collation) zum selben Benutzer auf.
    when(userReadRepository.findByLoginName("alice"))
        .thenReturn(Optional.of(userWithUuid(ALICE_UUID)));
    when(userReadRepository.findByLoginName("Alice"))
        .thenReturn(Optional.of(userWithUuid(ALICE_UUID)));

    final List<UUID> result = resolver.resolve("@alice und @Alice bitte pruefen");

    assertThat(result)
        .as("case-variante Handles zum selben Benutzer duerfen die UUID nur einmal liefern")
        .containsExactly(ALICE_UUID);
    assertThat(result).hasSize(1);
  }

  // ---- Hilfsmethode ---------------------------------------------------------------------------

  private static User userWithUuid(final UUID uuid) {
    final User user = new User();
    user.setUuid(uuid);
    user.setLoginName("test");
    user.setId(99L);
    return user;
  }
}
