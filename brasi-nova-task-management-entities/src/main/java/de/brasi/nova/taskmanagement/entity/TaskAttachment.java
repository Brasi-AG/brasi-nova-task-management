package de.brasi.nova.taskmanagement.entity;

import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import io.keel.framework.api.annotation.db.NovaColumn;
import io.keel.framework.api.annotation.db.NovaTable;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.scaffold.ScaffoldSchema;
import io.keel.framework.api.scaffold.ScaffoldSchemaConfig;
import io.keel.framework.api.scaffold.ScaffoldType;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Anhang an einer Aufgabe ({@code commentId == null}) oder an einem Kommentar. Haelt nur die
 * Metadaten; die Datei liegt im Dateisystem unter dem Speicherschluessel. Entfernen setzt den
 * Status auf DEACTIVE, die Datei bleibt.
 */
@NovaTable(name = "TASK_ANHANG")
@ScaffoldSchemaConfig(
    openApiGroupId = TaskManagementOpenApiGroups.TASK_MANAGEMENT,
    value = {@ScaffoldSchema(type = ScaffoldType.MODEL)})
@Getter
@Setter
@NoArgsConstructor
public class TaskAttachment extends NovaEntity<TaskAttachment> {

  private static final long serialVersionUID = 1L;

  /** Referenz auf die Aufgabe. */
  @NovaColumn(name = "TASK_ID", nullable = false)
  private Long taskId;

  /** Optionaler Kommentar (null = Anhang direkt an der Aufgabe). */
  @NovaColumn(name = "KOMMENTAR_ID", nullable = true)
  private Long commentId;

  /** Bereinigter Originaldateiname (hoechstens 255 Zeichen, Endung erhalten). */
  @NovaColumn(name = "DATEINAME", nullable = false, length = 255)
  private String fileName;

  /** Beim Hochladen gepruefte Inhaltsart (Content-Type der Auslieferung). */
  @NovaColumn(name = "MIME_TYP", nullable = false, length = 255)
  private String mimeType;

  /** Dateiendung ohne Punkt (aus der Erlaubtliste). */
  @NovaColumn(name = "DATEIENDUNG", nullable = false, length = 10)
  private String fileExtension;

  /** Dateigroesse in Bytes (immer groesser 0). */
  @NovaColumn(name = "GROESSE_BYTES", nullable = false)
  private long sizeBytes;

  /** SHA-256 des Inhalts als Hex (64 Zeichen); Grundlage des ETag. */
  @NovaColumn(name = "SHA256", nullable = false, length = 64)
  private String sha256;

  /**
   * Relativer Pfad der Datei unter dem Basispfad (Trenner {@code /}). Nur innerhalb der
   * Anlage-Transaktion {@code null}: der Schluessel entsteht aus der ID nach dem Insert.
   */
  @NovaColumn(name = "SPEICHERSCHLUESSEL", nullable = true, length = 400)
  private String storageKey;

  /** Kennzeichen: beim Hochladen wurde ein Vorschaubild erzeugt. */
  @NovaColumn(name = "HAT_VORSCHAU", nullable = false)
  private boolean thumbnailPresent;

  /** UUID der hochladenden Person (PROD_BENUTZER.EXTERNALID). */
  @NovaColumn(name = "HOCHGELADEN_VON_REF", nullable = false)
  private UUID uploadedByRef;
}
