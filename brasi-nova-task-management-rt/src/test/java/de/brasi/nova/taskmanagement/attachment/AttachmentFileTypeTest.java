package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.DOC;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.DOCX;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.JPEG;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PDF;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PNG;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PPT;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PPTX;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.TXT;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.XLS;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.XLSX;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.values;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link AttachmentFileType} (Intake #152, Task A3): Erlaubtliste der Endungen,
 * MIME-Werte, vorschaufaehige Typen und der Text der Erlaubtliste fuer Fehlermeldungen.
 */
class AttachmentFileTypeTest {

  @Test
  void i152_extension_lookup() {
    assertThat(AttachmentFileType.fromExtension("PDF")).contains(PDF);
    assertThat(AttachmentFileType.fromExtension("jpg")).contains(JPEG);
    assertThat(AttachmentFileType.fromExtension("jpeg")).contains(JPEG);
    assertThat(AttachmentFileType.fromExtension("exe")).isEmpty();
    assertThat(AttachmentFileType.fromExtension("")).isEmpty();
    assertThat(Arrays.stream(values()).filter(AttachmentFileType::previewable))
        .containsExactlyInAnyOrder(PDF, PNG, JPEG);
    assertThat(DOCX.mimeType())
        .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    // Vollstaendige Endungs- und MIME-Tabelle aus dem Interfaces-Block
    assertThat(values()).hasSize(10);
    assertThat(PDF.extensions()).containsExactly("pdf");
    assertThat(PNG.extensions()).containsExactly("png");
    assertThat(JPEG.extensions()).containsExactlyInAnyOrder("jpg", "jpeg");
    assertThat(TXT.extensions()).containsExactly("txt");
    assertThat(DOC.extensions()).containsExactly("doc");
    assertThat(DOCX.extensions()).containsExactly("docx");
    assertThat(XLS.extensions()).containsExactly("xls");
    assertThat(XLSX.extensions()).containsExactly("xlsx");
    assertThat(PPT.extensions()).containsExactly("ppt");
    assertThat(PPTX.extensions()).containsExactly("pptx");
    assertThat(PDF.mimeType()).isEqualTo("application/pdf");
    assertThat(PNG.mimeType()).isEqualTo("image/png");
    assertThat(JPEG.mimeType()).isEqualTo("image/jpeg");
    assertThat(TXT.mimeType()).isEqualTo("text/plain");
    assertThat(DOC.mimeType()).isEqualTo("application/msword");
    assertThat(XLS.mimeType()).isEqualTo("application/vnd.ms-excel");
    assertThat(XLSX.mimeType())
        .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(PPT.mimeType()).isEqualTo("application/vnd.ms-powerpoint");
    assertThat(PPTX.mimeType())
        .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    assertThat(AttachmentFileType.allowedExtensionsText())
        .isEqualTo("pdf, xls, xlsx, doc, docx, ppt, pptx, txt, png, jpg, jpeg");
  }
}
