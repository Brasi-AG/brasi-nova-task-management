package de.brasi.nova.taskmanagement.comment;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.mock.web.MockPart;

/**
 * Ein Teil {@code comment}, dessen Inhalt sich nicht lesen laesst: {@link #getInputStream()} wirft
 * eine {@link IOException}, deren Text einen Temp-Pfad des Servers nennt (so meldet es der
 * Servlet-Container, wenn die Temp-Datei eines Teils fehlt oder nicht lesbar ist).
 *
 * <p>Gedacht fuer die Tests des Lesefehlers in {@link CommentPartReader}: Weder der Text noch der
 * Pfad duerfen in die Fehlerantwort gelangen.
 */
final class UnreadablePart extends MockPart {

  /** Pfad, der nie in einer Antwort stehen darf. */
  static final String TEMP_PATH = "C:\\temp\\upload_123.tmp";

  /** Text der {@link IOException}; enthaelt den {@link #TEMP_PATH}. */
  static final String IO_MESSAGE = TEMP_PATH + " (Das System kann die Datei nicht finden)";

  UnreadablePart() {
    super("comment", new byte[0]);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    throw new IOException(IO_MESSAGE);
  }
}
