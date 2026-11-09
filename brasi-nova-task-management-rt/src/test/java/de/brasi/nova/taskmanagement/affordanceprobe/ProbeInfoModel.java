package de.brasi.nova.taskmanagement.affordanceprobe;

import io.keel.framework.api.service.model.InfoModel;
import java.util.List;
import java.util.UUID;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>Aeusseres {@link InfoModel} (Snapshot-Analogon), das eine Liste verschachtelter {@link
 * ProbeNestedInfoModel} traegt. Der {@code SelfLinkEnricher} laeuft rekursiv ueber verschachtelte
 * Collections von InfoModel-Instanzen.
 */
public class ProbeInfoModel extends InfoModel<ProbeInfoModel> {

  private List<ProbeNestedInfoModel> items;

  public ProbeInfoModel() {
    super();
  }

  public ProbeInfoModel(final UUID id, final List<ProbeNestedInfoModel> items) {
    super();
    setId(id);
    this.items = items;
  }

  public List<ProbeNestedInfoModel> getItems() {
    return items;
  }

  public void setItems(final List<ProbeNestedInfoModel> items) {
    this.items = items;
  }
}
