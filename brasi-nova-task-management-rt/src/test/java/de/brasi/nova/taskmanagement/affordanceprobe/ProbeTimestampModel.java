package de.brasi.nova.taskmanagement.affordanceprobe;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.keel.framework.api.service.model.BaseModel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0, P4-mech / R8 createdAt-Wire). KEIN Produktionscode.
 *
 * <p>{@link BaseModel}-Subklasse, die das fachliche Instant-Feld {@code createdInstant} ueber einen
 * Getter als JSON-Property {@code createdAt} exponiert, UND den geerbten
 * {@code LocalDateTime}-Getter {@code getCreatedAt()} per {@code @JsonIgnore} neutralisiert. So
 * erscheint {@code createdAt} im Draht GENAU EINMAL als ISO-8601-Instant (mit {@code Z}/Offset) und
 * nie als LocalDateTime.
 *
 * <p>COMPILE-FUSSNOTE (R8): Ein Versuch, das Feld selbst als {@code createdAt:Instant} zu
 * deklarieren und per Lombok {@code @Getter} zu exponieren, kann NICHT direkt {@code
 * getCreatedAt():LocalDateTime} aus {@link BaseModel} ueberschreiben — die Rueckgabetypen
 * ({@code Instant} vs. {@code LocalDateTime}) sind nicht kovariant, der Override ist
 * inkompatibel -> Compile-Fehler. Darum der hier gezeigte Weg: eigener Instant-Getter unter ANDEREM
 * Java-Namen ({@code getCreatedInstant}) + {@code @JsonProperty("createdAt")}, und separat der
 * geerbte Getter per {@code @JsonIgnore} stillgelegt. Die Inkompatibilitaet wurde einmalig lokal
 * verifiziert (siehe Report) und nicht als dauerhaftes Artefakt eingecheckt.
 */
public class ProbeTimestampModel extends BaseModel<ProbeTimestampModel> {

  private Instant createdInstant;

  public ProbeTimestampModel() {
    super();
  }

  public ProbeTimestampModel(final UUID id, final Instant createdInstant) {
    super(id);
    this.createdInstant = createdInstant;
  }

  /** Fachlicher Instant, als Wire-Property {@code createdAt} (ISO-8601 mit Offset/Z). */
  @JsonProperty("createdAt")
  public Instant getCreatedInstant() {
    return createdInstant;
  }

  public void setCreatedInstant(final Instant createdInstant) {
    this.createdInstant = createdInstant;
  }

  /** Geerbter LocalDateTime-createdAt wird NIE gesetzt und aus dem Draht ausgeblendet. */
  @Override
  @JsonIgnore
  public LocalDateTime getCreatedAt() {
    return super.getCreatedAt();
  }
}
