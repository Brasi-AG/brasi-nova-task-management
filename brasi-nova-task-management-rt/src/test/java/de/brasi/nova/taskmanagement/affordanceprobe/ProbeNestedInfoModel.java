package de.brasi.nova.taskmanagement.affordanceprobe;

import io.keel.framework.api.hateoas.annotation.SelfLinkTarget;
import io.keel.framework.api.service.model.InfoModel;
import java.util.UUID;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>Verschachteltes {@link InfoModel} mit {@code @SelfLinkTarget}. Der {@code SelfLinkEnricher}
 * fuegt genau {@link InfoModel}-Instanzen rekursiv einen {@code self}-Link hinzu; das Ziel stammt
 * aus dieser Annotation (controller + linkTo). P3 prueft, ob dafuer der servierende Handler unter
 * einem Regel-Owner liegen muss.
 */
@SelfLinkTarget(controller = ProbeOwnerController.class, linkTo = "probe_owner_get_by_id")
public class ProbeNestedInfoModel extends InfoModel<ProbeNestedInfoModel> {

  private String note;

  public ProbeNestedInfoModel() {
    super();
  }

  public ProbeNestedInfoModel(final UUID id, final String note) {
    super();
    setId(id);
    this.note = note;
  }

  public String getNote() {
    return note;
  }

  public void setNote(final String note) {
    this.note = note;
  }
}
