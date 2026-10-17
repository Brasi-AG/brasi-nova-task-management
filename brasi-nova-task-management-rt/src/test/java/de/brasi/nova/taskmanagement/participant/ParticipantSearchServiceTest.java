package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import de.brasi.nova.taskmanagement.participant.model.ParticipantPage;
import de.brasi.nova.taskmanagement.participant.model.ParticipantSummary;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit-Tests fuer {@link ParticipantSearchService} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale:
 * <ol>
 *   <li>hasMore + nextCursor (limit+1-Logik)
 *   <li>limit-Clamp [1, 100]
 *   <li>q blank -> kein LIKE-Filter; q vorhanden -> LIKE-escape
 *   <li>Cursor-Roundtrip (KeysetCursor reuse)
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParticipantSearchServiceTest {

  private static final long TENANT_ID = 550L;

  @Mock private UserReadRepository userReadRepository;

  private ParticipantSearchService service;

  @BeforeEach
  void setUp() {
    service = new ParticipantSearchService(userReadRepository);
  }

  // ---- hasMore + nextCursor -------------------------------------------------------------------

  @Test
  @DisplayName("hasMore_und_nextCursor_wenn_mehr_als_limit_eintraege: limit+1 Abfrage -> hasMore=true")
  void hasMore_und_nextCursor_wenn_mehr_als_limit_eintraege() {
    final int limit = 3;
    // Repository liefert limit+1 Eintraege
    final List<User> users = buildUsers(limit + 1);
    when(userReadRepository.findFirstPage(isNull(), eq(limit + 1))).thenReturn(users);

    final ParticipantPage page = service.search(TENANT_ID, null, null, limit);

    assertThat(page.hasMore()).isTrue();
    assertThat(page.items()).hasSize(limit);
    assertThat(page.nextCursor()).isNotNull().isNotBlank();
  }

  @Test
  @DisplayName("hasMore_false_wenn_letzte_seite: genau limit Eintraege -> hasMore=false")
  void hasMore_false_wenn_letzte_seite() {
    final int limit = 5;
    final List<User> users = buildUsers(limit);
    when(userReadRepository.findFirstPage(isNull(), eq(limit + 1))).thenReturn(users);

    final ParticipantPage page = service.search(TENANT_ID, null, null, limit);

    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
    assertThat(page.items()).hasSize(limit);
  }

  // ---- limit-Clamp [1, 100] ------------------------------------------------------------------

  @Test
  @DisplayName("limit_clamp_0_wird_zu_1: limit=0 wird auf 1 geclampt")
  void limit_clamp_0_wird_zu_1() {
    when(userReadRepository.findFirstPage(isNull(), eq(2))).thenReturn(List.of());

    service.search(TENANT_ID, null, null, 0);

    verify(userReadRepository).findFirstPage(isNull(), eq(2)); // 1+1
  }

  @Test
  @DisplayName("limit_clamp_200_wird_zu_100: limit=200 wird auf 100 geclampt")
  void limit_clamp_200_wird_zu_100() {
    when(userReadRepository.findFirstPage(isNull(), eq(101))).thenReturn(List.of());

    service.search(TENANT_ID, null, null, 200);

    verify(userReadRepository).findFirstPage(isNull(), eq(101)); // 100+1
  }

  // ---- q-blank = kein Filter -----------------------------------------------------------------

  @Test
  @DisplayName("q_blank_kein_like_filter: leeres q -> findFirstPage mit like=null")
  void q_blank_kein_like_filter() {
    when(userReadRepository.findFirstPage(isNull(), anyInt())).thenReturn(List.of());

    service.search(TENANT_ID, "  ", null, 10);

    verify(userReadRepository).findFirstPage(isNull(), eq(11));
  }

  @Test
  @DisplayName("q_null_kein_like_filter: null q -> findFirstPage mit like=null")
  void q_null_kein_like_filter() {
    when(userReadRepository.findFirstPage(isNull(), anyInt())).thenReturn(List.of());

    service.search(TENANT_ID, null, null, 10);

    verify(userReadRepository).findFirstPage(isNull(), eq(11));
  }

  @Test
  @DisplayName("q_vorhanden_like_escaped: q='test' -> LIKE '%test%' mit escape")
  void q_vorhanden_like_escaped() {
    final String q = "test";
    when(userReadRepository.findFirstPage(eq("%test%"), eq(11))).thenReturn(List.of());

    service.search(TENANT_ID, q, null, 10);

    verify(userReadRepository).findFirstPage(eq("%test%"), eq(11));
  }

  @Test
  @DisplayName("q_mit_sonderzeichen_escaped: q='50%' -> LIKE '%50[%]%'")
  void q_mit_sonderzeichen_escaped() {
    final String q = "50%";
    when(userReadRepository.findFirstPage(eq("%50[%]%"), eq(11))).thenReturn(List.of());

    service.search(TENANT_ID, q, null, 10);

    verify(userReadRepository).findFirstPage(eq("%50[%]%"), eq(11));
  }

  @Test
  @DisplayName("q_mit_unterstrich_escaped: q='a_b' -> LIKE '%a[_]b%'")
  void q_mit_unterstrich_escaped() {
    final String q = "a_b";
    when(userReadRepository.findFirstPage(eq("%a[_]b%"), eq(11))).thenReturn(List.of());

    service.search(TENANT_ID, q, null, 10);

    verify(userReadRepository).findFirstPage(eq("%a[_]b%"), eq(11));
  }

  // ---- Cursor-Roundtrip -----------------------------------------------------------------------

  @Test
  @DisplayName("cursor_roundtrip: nextCursor von Seite1 kann fuer Seite2 dekodiert werden")
  void cursor_roundtrip() {
    final int limit = 2;
    final List<User> page1Users = buildUsers(limit + 1); // hasMore=true
    when(userReadRepository.findFirstPage(isNull(), eq(limit + 1))).thenReturn(page1Users);

    final ParticipantPage page1 = service.search(TENANT_ID, null, null, limit);
    assertThat(page1.hasMore()).isTrue();
    assertThat(page1.nextCursor()).isNotNull();

    // Cursor dekodieren: muss 1 Token enthalten (die EXTERNALID des letzten Elements)
    final List<String> tokens = KeysetCursor.decode(page1.nextCursor(), 1);
    assertThat(tokens).hasSize(1);
    // Token muss die UUID des letzten Elements (page1.items().last()) sein
    final ParticipantSummary last = page1.items().get(page1.items().size() - 1);
    assertThat(tokens.get(0)).isEqualTo(last.userRef().toString());

    // Seite 2 abrufen: findPage wird aufgerufen
    final UUID afterExternalId = UUID.fromString(tokens.get(0));
    final List<User> page2Users = buildUsers(1); // 1 Eintrag, keine weitere Seite
    when(userReadRepository.findPage(isNull(), eq(afterExternalId), eq(limit + 1)))
        .thenReturn(page2Users);

    final ParticipantPage page2 = service.search(TENANT_ID, null, page1.nextCursor(), limit);
    assertThat(page2.hasMore()).isFalse();
    assertThat(page2.items()).hasSize(1);
  }

  // ---- Hilfsmethode ---------------------------------------------------------------------------

  private List<User> buildUsers(final int count) {
    final List<User> users = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final User u = new User();
      u.setUuid(UUID.randomUUID());
      u.setId(1000002000L + i);
      u.setLoginName("user" + i + "@test.de");
      u.setFullName("Test User " + i);
      u.setTenantId(TENANT_ID);
      users.add(u);
    }
    return users;
  }
}
