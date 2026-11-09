package de.brasi.nova.taskmanagement.security.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit-Test (ohne DB) fuer die Fehlercode-Klassifizierung des Wegwerf-DB-Drops. */
class RefSchemaItDbTest {

  @Test
  @DisplayName("drop_fehlercodes_3702_und_5064_sind_retrybar_andere_nicht")
  void drop_fehlercodes_3702_und_5064_sind_retrybar_andere_nicht() {
    assertThat(RefSchemaItDb.isRetryableDropErrorCode(3702)).isTrue();
    assertThat(RefSchemaItDb.isRetryableDropErrorCode(5064)).isTrue();
    assertThat(RefSchemaItDb.isRetryableDropErrorCode(0)).isFalse();
    assertThat(RefSchemaItDb.isRetryableDropErrorCode(911)).isFalse();
    assertThat(RefSchemaItDb.isRetryableDropErrorCode(5063)).isFalse();
  }
}
