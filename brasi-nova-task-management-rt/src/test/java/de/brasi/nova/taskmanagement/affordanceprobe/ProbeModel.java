package de.brasi.nova.taskmanagement.affordanceprobe;

import io.keel.framework.api.service.model.BaseModel;
import java.util.UUID;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>Minimales {@link BaseModel} fuer die Affordance-Proben P1/P2/P5. Traegt nur ein fachliches
 * Textfeld; Links werden ausschliesslich durch den KSF-Enrichment-Pfad gesetzt (nie manuell).
 */
public class ProbeModel extends BaseModel<ProbeModel> {

  private String label;

  public ProbeModel() {
    super();
  }

  public ProbeModel(final UUID id, final String label) {
    super(id);
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }
}
