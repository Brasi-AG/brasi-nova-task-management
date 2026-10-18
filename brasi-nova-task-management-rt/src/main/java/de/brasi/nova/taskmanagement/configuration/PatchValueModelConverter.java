package de.brasi.nova.taskmanagement.configuration;

import com.fasterxml.jackson.databind.JavaType;
import io.keel.framework.api.service.model.PatchValue;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;

/**
 * springdoc/swagger-{@link ModelConverter}, der den KSF-Drei-Wert-PATCH-Wrapper
 * {@link PatchValue}&lt;T&gt; auf seinen fachlichen Typ {@code T} reduziert.
 *
 * <p>Ohne diesen Converter rendert springdoc 2.8.12 {@code PatchValue<T>} als eigenstaendiges
 * Schema (mit den Interna {@code value}/{@code present}) und referenziert dieses aus den PATCH-DTOs
 * (z.B. {@code TaskPatchInput}, {@code TagUpdateInput}). Die OpenAPI-Doku zeigte damit die
 * Wrapper-Struktur statt des fachlichen Werts - ein bekannter springdoc-Fallstrick
 * (production: KSF-PatchValue-OpenAPI-Wrapper -> ModelConverter-Fix).
 *
 * <p>Der Converter loest statt {@code PatchValue<T>} den inneren Typ {@code T} auf und reicht die
 * Feld-Annotationen ({@code @Schema}/{@code @ArraySchema} am Record-Component) ueber
 * {@code ctxAnnotations} durch, sodass Beschreibungen/Beispiele erhalten bleiben. Wird von springdoc
 * automatisch registriert, da es als Spring-Bean exponiert ist.
 */
public class PatchValueModelConverter implements ModelConverter {

  @Override
  public Schema<?> resolve(
      final AnnotatedType type,
      final ModelConverterContext context,
      final Iterator<ModelConverter> chain) {
    final JavaType javaType = Json.mapper().constructType(type.getType());
    if (javaType != null
        && PatchValue.class.isAssignableFrom(javaType.getRawClass())
        && javaType.containedTypeCount() == 1) {
      final JavaType inner = javaType.containedType(0);
      final AnnotatedType unwrapped =
          new AnnotatedType()
              .type(inner)
              .ctxAnnotations(type.getCtxAnnotations())
              .parent(type.getParent())
              .name(type.getName())
              .propertyName(type.getPropertyName())
              .schemaProperty(type.isSchemaProperty())
              .resolveAsRef(type.isResolveAsRef())
              .jsonViewAnnotation(type.getJsonViewAnnotation())
              .skipOverride(type.isSkipOverride());
      final Schema<?> resolved = context.resolve(unwrapped);
      // Nullable am Feld-@Schema explizit durchreichen: bei der Aufloesung des inneren Typs geht
      // die Nullability (OpenAPI 3.1: "null" im type-Set) sonst verloren. PATCH-Felder mit
      // PatchValue.ofNull() duerfen explizit null gesendet werden.
      if (resolved != null && type.getCtxAnnotations() != null) {
        for (final java.lang.annotation.Annotation ann : type.getCtxAnnotations()) {
          if (ann instanceof io.swagger.v3.oas.annotations.media.Schema schemaAnn
              && schemaAnn.nullable()) {
            resolved.setNullable(Boolean.TRUE);
          }
        }
      }
      return resolved;
    }
    return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
  }
}
