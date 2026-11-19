package de.brasi.nova.taskmanagement.attachment;

import java.io.InputStream;
import java.util.Optional;
import org.springframework.core.io.InputStreamSource;

/**
 * Zugriff auf die Dateien der Anhaenge (Intake #152).
 *
 * <p>Die Abstraktion trennt die Anhang-Logik vom Speicherort, damit die Dateien spaeter ohne
 * Aenderung der Fachlogik in das Dokumentenarchiv wandern koennen. Alle Dateien werden ueber einen
 * relativen Speicherschluessel angesprochen (siehe {@link AttachmentStorageKeys}). Geschriebene
 * Dateien werden nie ueberschrieben und nie veraendert.
 */
public interface AttachmentContentProvider {

  /**
   * Schreibt die Inhaltsdatei eines Anhangs atomar: erst in eine temporaere Datei im Zielordner,
   * dann durch Umbenennen an den Zielort. Es entsteht nie eine halb geschriebene Datei unter dem
   * endgueltigen Namen; bei jedem Fehler bleibt keine temporaere Datei zurueck.
   *
   * @param storageKey relativer Speicherschluessel der Inhaltsdatei
   * @param source Quelle des Inhalts; der Strom wird gelesen und geschlossen
   * @throws IllegalStateException wenn die Datei bereits existiert (sie wird nie ueberschrieben),
   *     der Schluessel ausserhalb des Basispfads liegt oder der Basispfad nicht konfiguriert ist
   * @throws java.io.UncheckedIOException bei einem Fehler beim Lesen der Quelle oder Schreiben
   */
  void writeContent(String storageKey, InputStreamSource source);

  /**
   * Schreibt das Vorschaubild neben die Inhaltsdatei, mit derselben atomaren Mechanik wie {@link
   * #writeContent(String, InputStreamSource)}. Ein vorhandenes Vorschaubild bleibt unveraendert,
   * das ist kein Fehler.
   *
   * @param contentKey Speicherschluessel der Inhaltsdatei (nicht des Vorschaubilds)
   * @param jpeg Inhalt des Vorschaubilds als JPEG
   * @throws IllegalStateException wenn der Schluessel ausserhalb des Basispfads liegt oder der
   *     Basispfad nicht konfiguriert ist
   * @throws java.io.UncheckedIOException bei einem Fehler beim Schreiben
   */
  void writeThumbnail(String contentKey, byte[] jpeg);

  /**
   * Oeffnet die Inhaltsdatei zum Lesen.
   *
   * @param storageKey relativer Speicherschluessel der Inhaltsdatei
   * @return Strom der Datei (vom Aufrufer zu schliessen); leer, wenn die Datei fehlt
   * @throws IllegalStateException wenn der Schluessel ausserhalb des Basispfads liegt oder der
   *     Basispfad nicht konfiguriert ist
   * @throws java.io.UncheckedIOException bei einem anderen Fehler beim Oeffnen
   */
  Optional<InputStream> openContent(String storageKey);

  /**
   * Oeffnet das Vorschaubild zum Lesen.
   *
   * @param contentKey Speicherschluessel der Inhaltsdatei (nicht des Vorschaubilds)
   * @return Strom des Vorschaubilds (vom Aufrufer zu schliessen); leer, wenn es fehlt
   * @throws IllegalStateException wenn der Schluessel ausserhalb des Basispfads liegt oder der
   *     Basispfad nicht konfiguriert ist
   * @throws java.io.UncheckedIOException bei einem anderen Fehler beim Oeffnen
   */
  Optional<InputStream> openThumbnail(String contentKey);

  /**
   * Loescht Inhaltsdatei und Vorschaubild. Nur fuer das Aufraeumen beim Zurueckrollen einer
   * Transaktion gedacht (Anhaenge werden fachlich nie physisch geloescht). Fehler, auch eine
   * fehlende Datei, werden nur protokolliert und nie geworfen.
   *
   * @param contentKey Speicherschluessel der Inhaltsdatei
   */
  void deleteQuietly(String contentKey);
}
