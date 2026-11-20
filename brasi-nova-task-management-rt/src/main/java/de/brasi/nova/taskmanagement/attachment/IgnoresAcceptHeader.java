package de.brasi.nova.taskmanagement.attachment;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert eine Controller-Methode, deren Antworten den {@code Accept}-Header des Clients
 * ignorieren (Intake #152, Nachbesserung): Der Erfolg liefert den Binaerinhalt mit seinem eigenen
 * {@code Content-Type}, ein Fehler kommt immer als JSON im KSF-Format ({@code ApiError}), auch wenn
 * der Client nur einen anderen Typ annimmt (etwa {@code image/png} oder {@code application/pdf}).
 *
 * <p>Warum: Ohne die Markierung versucht Spring, den Fehlerkoerper nach {@code Accept}
 * auszuhandeln. Nimmt der Client kein JSON an, scheitert das Schreiben des Fehlers, und die Anfrage
 * endet mit 500 statt mit dem fachlichen Status (404, 400, 503). Die Auswertung der Markierung
 * steht in {@link AttachmentAcceptConfiguration}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IgnoresAcceptHeader {}
