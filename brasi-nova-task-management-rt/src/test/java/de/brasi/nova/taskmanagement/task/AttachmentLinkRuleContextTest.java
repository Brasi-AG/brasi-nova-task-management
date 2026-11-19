package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.keel.framework.api.hateoas.annotation.LinkContext;
import io.keel.framework.api.hateoas.annotation.LinkRule;
import io.keel.framework.api.hateoas.annotation.LinkRules;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Prueft ohne Spring-Kontext, dass die Rels {@code attachments} und {@code add-attachment} nur am
 * Aufgaben-Detail erscheinen (Intake #152): Die Link-Regeln am {@link TaskFacadeController} tragen
 * {@link LinkContext#DETAIL_ONLY}, alle uebrigen Item-Regeln bleiben in Liste und Detail
 * ({@link LinkContext#ALL}). Die Listenzeilen der Inbox behalten damit ihre bisherige Rel-Menge.
 */
class AttachmentLinkRuleContextTest {

  @Test
  void i152_attachment_rels_are_detail_only() {
    final Map<String, LinkContext> contexts = contextsByRel();

    assertThat(contexts)
        .containsEntry(TaskHateoas.Rels.ATTACHMENTS, LinkContext.DETAIL_ONLY)
        .containsEntry(TaskHateoas.Rels.ADD_ATTACHMENT, LinkContext.DETAIL_ONLY);
  }

  @Test
  void i152_other_item_rels_stay_in_list_and_detail() {
    final Map<String, LinkContext> contexts = contextsByRel();
    contexts.remove(TaskHateoas.Rels.ATTACHMENTS);
    contexts.remove(TaskHateoas.Rels.ADD_ATTACHMENT);

    assertThat(contexts).isNotEmpty();
    assertThat(contexts.values()).containsOnly(LinkContext.ALL);
  }

  private static Map<String, LinkContext> contextsByRel() {
    final LinkRules rules = TaskFacadeController.class.getAnnotation(LinkRules.class);
    assertThat(rules).isNotNull();
    return Arrays.stream(rules.value()).collect(Collectors.toMap(LinkRule::rel, LinkRule::context));
  }
}
