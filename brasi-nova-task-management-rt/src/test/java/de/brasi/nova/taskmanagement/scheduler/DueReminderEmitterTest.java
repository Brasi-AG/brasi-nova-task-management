package de.brasi.nova.taskmanagement.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.brasi.nova.taskmanagement.entity.TaskReminderReceipt;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.repository.BaseTaskReminderReceiptRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit-Tests fuer die laengen-/surrogat-sichere Text-Kappung in {@link DueReminderEmitter}. */
class DueReminderEmitterTest {

  @Test
  @DisplayName("truncate_null_und_kurz_bleiben_unveraendert")
  void truncate_null_und_kurz_bleiben_unveraendert() {
    assertThat(DueReminderEmitter.truncate(null, 500)).isNull();
    assertThat(DueReminderEmitter.truncate("kurz", 500)).isEqualTo("kurz");
    assertThat(DueReminderEmitter.truncate("genau", 5)).isEqualTo("genau");
  }

  @Test
  @DisplayName("truncate_kappt_ueberlange_strings_auf_maxLength")
  void truncate_kappt_ueberlange_strings_auf_maxLength() {
    final String value = "a".repeat(600);
    final String result = DueReminderEmitter.truncate(value, 500);
    assertThat(result).hasSize(500);
  }

  @Test
  @DisplayName("truncate_zerschneidet_kein_surrogatpaar")
  void truncate_zerschneidet_kein_surrogatpaar() {
    // Ein Emoji (U+1F600) ist ein Surrogatpaar (2 UTF-16-Code-Units). Es sitzt genau auf der
    // Grenze: 499 Fueller-Zeichen + Emoji -> Grenze bei 500 wuerde das Paar (499/500) trennen.
    final String emoji = "😀"; // GRINNING FACE
    final String value = "a".repeat(499) + emoji + "b".repeat(50);

    final String result = DueReminderEmitter.truncate(value, 500);

    // Das halbe Surrogat darf NICHT uebrig bleiben -> auf 499 gekappt, kein ungepaartes Surrogat.
    assertThat(result).hasSize(499);
    assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1)))
        .as("kein ungepaartes High-Surrogate am Ende")
        .isFalse();
    assertThat(result.codePoints().anyMatch(cp -> cp == 0xD83D || cp == 0xDE00))
        .as("kein halbes Surrogat im Ergebnis")
        .isFalse();
  }

  @Test
  @DisplayName("truncate_behaelt_vollstaendiges_surrogatpaar_vor_der_grenze")
  void truncate_behaelt_vollstaendiges_surrogatpaar_vor_der_grenze() {
    // Emoji endet bei Index 499/500 nicht auf der Grenze -> vollstaendiges Paar bleibt erhalten.
    final String emoji = "😀";
    final String value = "a".repeat(498) + emoji + "b".repeat(50);

    final String result = DueReminderEmitter.truncate(value, 500);

    assertThat(result).hasSize(500);
    assertThat(result).endsWith(emoji);
  }

  // ---- Intake #158: stumme Empfaenger erhalten keine DUE-Benachrichtigung ---------------------

  @Test
  @DisplayName(
      "emitOnce_stumm: Quittung und snapshot-invalidated wie bisher, aber KEINE DUE-Benachrichtigung")
  void emitOnce_stumm_schreibt_quittung_und_signal_ohne_benachrichtigung() {
    final BaseTaskReminderReceiptRepository receipts =
        mock(BaseTaskReminderReceiptRepository.class);
    final NotificationService notifications = mock(NotificationService.class);
    final InboxSignalService signals = mock(InboxSignalService.class);
    final DueReminderEmitter emitter = new DueReminderEmitter(receipts, notifications, signals);
    final UUID recipient = UUID.randomUUID();

    emitter.emitOnce(7L, 70L, recipient, LocalDate.now(), "Faellig", true);

    verify(receipts, times(1)).save(any(TaskReminderReceipt.class));
    verify(notifications, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(signals, times(1)).invalidate(7L, List.of(recipient));
  }

  @Test
  @DisplayName("emitOnce_laut: Quittung, DUE-Benachrichtigung und Signal (unveraendert)")
  void emitOnce_laut_emittiert_due() {
    final BaseTaskReminderReceiptRepository receipts =
        mock(BaseTaskReminderReceiptRepository.class);
    final NotificationService notifications = mock(NotificationService.class);
    final InboxSignalService signals = mock(InboxSignalService.class);
    final DueReminderEmitter emitter = new DueReminderEmitter(receipts, notifications, signals);
    final UUID recipient = UUID.randomUUID();

    emitter.emitOnce(7L, 70L, recipient, LocalDate.now(), "Faellig", false);

    verify(receipts, times(1)).save(any(TaskReminderReceipt.class));
    verify(notifications, times(1))
        .emit(eq(recipient), eq(70L), eq(NotificationTypeCodes.DUE), any(), any(), any(), any());
    verify(signals, times(1)).invalidate(7L, List.of(recipient));
  }
}
