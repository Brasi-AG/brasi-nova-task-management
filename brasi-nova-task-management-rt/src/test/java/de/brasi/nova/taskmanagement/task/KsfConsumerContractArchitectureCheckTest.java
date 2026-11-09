package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.brasi.nova.taskmanagement.BrasiNovaApplication;
import de.brasi.nova.taskmanagement.affordanceprobe.JpaArchitectureViolationFixture;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/**
 * Stehender Architektur-Waechter: beweist, dass {@code brasi-nova-task-management-rt} KEINE direkte
 * JPA-, Hibernate- oder Spring-Data-Persistence einfuehrt (KSF-Consumer-Contract-Sentinel, PLAN
 * #141 Slice 6).
 *
 * <p>task-management ist KSF-nativ: Entities erben von {@code NovaEntity}, Repositories erben von
 * KSF {@code Base*Repository}/{@code NovaRepository}. Direkte {@code jakarta.persistence.*},
 * {@code javax.persistence.*}, {@code org.springframework.data.jpa.*}, {@code org.hibernate.*},
 * {@code org.springframework.data.jdbc.*}/{@code relational.*} sowie die Spring-Data-Repository-
 * Basistypen sind VERBOTEN.
 *
 * <h2>ERLAUBT (darf NICHT false-positiv schlagen - kalibriert gegen IST-Code)</h2>
 *
 * <ul>
 *   <li>{@code org.springframework.data.repository.query.Param} - das {@code @Param} fuer
 *       KSF-{@code @NovaQuery}-Named-Parameter (alle {@code *QueryRepository}-Dateien).
 *   <li>KSF {@code io.keel.framework.*} Annotationen und Basistypen.
 * </ul>
 *
 * <h2>Scan-Design</h2>
 *
 * <p>Constant-Pool-Scan der kompilierten PRODUKTIONS-Klassen ({@code target/classes}). Jeder
 * {@code CONSTANT_Utf8}-Eintrag (Klassennamen, Feld-/Methoden-Deskriptoren, Annotations-
 * Deskriptoren, Signaturen) wird gegen die Verbotsliste geprueft. Damit werden auch Feld- und
 * Methoden-Annotationen sowie injizierte Typen (z. B. {@code EntityManager}) erkannt, nicht nur
 * Klassen-Annotationen und direkte Supertypen. Es wird bewusst der VOLLE Bestand gescannt (nicht
 * diff-basiert): strenger und unabhaengig vom Git-Stand.
 *
 * <p>Fail-closed: Lese- oder Klassenformat-Fehler werden NICHT verschluckt; ein leerer Scan oder
 * ein Scan ohne {@link BrasiNovaApplication} schlaegt fehl.
 *
 * <h2>Invertierbarkeit</h2>
 *
 * <p>Synthetische Klassen (Springs repackaged ASM) belegen fuer JEDEN Erkennungszweig, dass er
 * anschlaegt; eine erlaubte {@code @Param}-Referenz schlaegt NICHT an. Die javac-kompilierte
 * Fixture {@link JpaArchitectureViolationFixture} (CrudRepository-Supertyp) bleibt als Beleg fuer
 * echte Compiler-Ausgabe bestehen.
 */
class KsfConsumerContractArchitectureCheckTest {

  /** Verbotene Paket-Praefixe (interne Namen, enden auf {@code /}). */
  private static final List<String> FORBIDDEN_PACKAGES =
      List.of(
          "jakarta/persistence/",
          "javax/persistence/",
          "org/springframework/data/jpa/",
          "org/hibernate/",
          "org/springframework/data/jdbc/",
          "org/springframework/data/relational/");

  /** Verbotene Einzeltypen (interne Namen, exakte Grenze - kein Praefix-Treffer). */
  private static final List<String> FORBIDDEN_TYPES =
      List.of(
          "org/springframework/data/repository/Repository",
          // Annotation = Spring-Data-Repository-Deklaration (@RepositoryDefinition)
          "org/springframework/data/repository/RepositoryDefinition",
          "org/springframework/data/repository/CrudRepository",
          "org/springframework/data/repository/ListCrudRepository",
          "org/springframework/data/repository/PagingAndSortingRepository",
          "org/springframework/data/repository/ListPagingAndSortingRepository",
          "org/springframework/data/repository/reactive/ReactiveCrudRepository",
          "org/springframework/data/jpa/repository/JpaSpecificationExecutor",
          "org/springframework/data/repository/query/QueryByExampleExecutor");

  private static final String ALLOWED_PARAM = "org/springframework/data/repository/query/Param";

  /** Namensgrenze: davor/danach darf kein Bezeichner-/Pfadzeichen stehen. */
  private static final Pattern FORBIDDEN_PATTERN = buildPattern();

  private static Pattern buildPattern() {
    final StringBuilder alternatives = new StringBuilder();
    for (final String pkg : FORBIDDEN_PACKAGES) {
      alternatives.append(Pattern.quote(pkg)).append("|");
    }
    for (final String type : FORBIDDEN_TYPES) {
      alternatives.append(Pattern.quote(type)).append("(?![A-Za-z0-9_$/])|");
    }
    alternatives.setLength(alternatives.length() - 1);
    return Pattern.compile("(?<![A-Za-z0-9_$/])L?(?:" + alternatives + ")");
  }

  /** Ergebnis eines Verzeichnis-Scans. */
  private record ScanResult(int classCount, boolean applicationScanned, List<String> violations) {}

  // ---------------------------------------------------------------------------
  // Teil 1: Produktionscode ist GRUEN
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "ksf_consumer_contract: kein JPA / Hibernate / Spring-Data-Persistence im Produktionscode"
          + " (Constant-Pool-Voll-Scan, fail-closed)")
  void ksf_consumer_contract_kein_jpa_in_produktionscode() throws Exception {
    final Path productionClassesDir = resolveProductionClassesDir();
    assertThat(productionClassesDir).as("target/classes muss vorhanden sein").isDirectory();

    final ScanResult result = scanDirectory(productionClassesDir);
    requireNonEmpty(result);

    System.out.println(
        "[ARCH-CHECK] gescannte Produktionsklassen=" + result.classCount() + " Verletzungen="
            + result.violations());
    assertThat(result.violations())
        .as(
            "KSF-Consumer-Contract verletzt: direkte JPA/Hibernate/Spring-Data-Persistence im"
                + " Produktionscode. task-management nutzt KSF-native Repositories.")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "ksf_consumer_contract_dependency_waechter: JPA-/Hibernate-Klassen liegen nicht auf dem"
          + " Classpath")
  void ksf_consumer_contract_dependency_waechter() {
    for (final String forbidden :
        List.of(
            "jakarta.persistence.EntityManager",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.hibernate.Session")) {
      assertThatThrownBy(() -> Class.forName(forbidden))
          .as("%s darf nicht auf dem Classpath liegen (Consumer-Contract)", forbidden)
          .isInstanceOf(ClassNotFoundException.class);
    }
  }

  @Test
  @DisplayName("ksf_consumer_contract_nicht_leer_guard: leerer/fremder Scan schlaegt fehl")
  void ksf_consumer_contract_nicht_leer_guard(@TempDir final Path tempDir) throws Exception {
    // Leeres Verzeichnis -> Guard schlaegt an.
    assertThatThrownBy(() -> requireNonEmpty(scanDirectory(tempDir)))
        .isInstanceOf(AssertionError.class);

    // Klassen vorhanden, aber ohne BrasiNovaApplication -> Guard schlaegt an.
    Files.write(tempDir.resolve("Other.class"), synthetic("synthetic/Other", null, null));
    final ScanResult other = scanDirectory(tempDir);
    assertThat(other.classCount()).isEqualTo(1);
    assertThatThrownBy(() -> requireNonEmpty(other)).isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName(
      "ksf_consumer_contract_kein_stilles_schlucken: defekte .class-Datei laesst den Scan"
          + " fehlschlagen")
  void ksf_consumer_contract_defekte_class_datei_schlaegt_fehl(@TempDir final Path tempDir)
      throws Exception {
    Files.write(tempDir.resolve("Broken.class"), new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
    assertThatThrownBy(() -> scanDirectory(tempDir)).isInstanceOf(IllegalStateException.class);

    // Abgeschnittene (aber magic-korrekte) Datei -> IO-Fehler wird nicht verschluckt.
    final byte[] valid = synthetic("synthetic/Cut", null, null);
    Files.write(tempDir.resolve("Broken.class"), java.util.Arrays.copyOf(valid, 20));
    assertThatThrownBy(() -> scanDirectory(tempDir))
        .isInstanceOfAny(IllegalStateException.class, UncheckedIOException.class);
  }

  // ---------------------------------------------------------------------------
  // Teil 2: Invertierbarkeit - JEDER Erkennungszweig schlaegt an
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("invertierbar_feldtyp: Feld vom Typ jakarta.persistence.EntityManager")
  void invertierbar_feldtyp() throws Exception {
    final byte[] bytes =
        synthetic("synthetic/FieldType", cw -> cw.visitField(
            Opcodes.ACC_PRIVATE, "em", "Ljakarta/persistence/EntityManager;", null, null).visitEnd(),
            null);
    assertThat(scanBytes("FieldType", bytes)).anyMatch(v -> v.contains("jakarta/persistence/"));
  }

  @Test
  @DisplayName("invertierbar_feld_annotation: Feld-Annotation @PersistenceContext")
  void invertierbar_feld_annotation() throws Exception {
    final byte[] bytes =
        synthetic("synthetic/FieldAnn", cw -> {
          final var fv = cw.visitField(Opcodes.ACC_PRIVATE, "x", "Ljava/lang/Object;", null, null);
          fv.visitAnnotation("Ljakarta/persistence/PersistenceContext;", true).visitEnd();
          fv.visitEnd();
        }, null);
    assertThat(scanBytes("FieldAnn", bytes)).anyMatch(v -> v.contains("PersistenceContext"));
  }

  @Test
  @DisplayName("invertierbar_methoden_annotation: Methoden-Annotation @PersistenceContext")
  void invertierbar_methoden_annotation() throws Exception {
    final byte[] bytes =
        synthetic("synthetic/MethodAnn", null, cw -> {
          final MethodVisitor mv =
              cw.visitMethod(Opcodes.ACC_PUBLIC, "m", "()V", null, null);
          mv.visitAnnotation("Ljakarta/persistence/PersistenceContext;", true).visitEnd();
          mv.visitCode();
          mv.visitInsn(Opcodes.RETURN);
          mv.visitMaxs(0, 1);
          mv.visitEnd();
        });
    assertThat(scanBytes("MethodAnn", bytes)).anyMatch(v -> v.contains("PersistenceContext"));
  }

  @Test
  @DisplayName("invertierbar_klassen_annotation: Klassen-Annotation aus org.hibernate")
  void invertierbar_klassen_annotation() throws Exception {
    final byte[] bytes = syntheticWithClassAnnotation("synthetic/ClassAnn", "Lorg/hibernate/annotations/Immutable;");
    assertThat(scanBytes("ClassAnn", bytes)).anyMatch(v -> v.contains("org/hibernate/"));
  }

  @Test
  @DisplayName("invertierbar_methodenaufruf: Methodenaufruf auf org.hibernate.Session")
  void invertierbar_methodenaufruf() throws Exception {
    final byte[] bytes =
        synthetic("synthetic/Call", null, cw -> {
          final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", "()V", null, null);
          mv.visitCode();
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC, "org/hibernate/Session", "x", "()V", true);
          mv.visitInsn(Opcodes.RETURN);
          mv.visitMaxs(0, 1);
          mv.visitEnd();
        });
    assertThat(scanBytes("Call", bytes)).anyMatch(v -> v.contains("org/hibernate/"));
  }

  @Test
  @DisplayName("invertierbar_verbotene_pakete: jedes verbotene Paket schlaegt als Feldtyp an")
  void invertierbar_verbotene_pakete() throws Exception {
    for (final String pkg : FORBIDDEN_PACKAGES) {
      final String desc = "L" + pkg + "Anything;";
      final byte[] bytes =
          synthetic("synthetic/Pkg", cw -> cw.visitField(Opcodes.ACC_PRIVATE, "f", desc, null, null).visitEnd(), null);
      assertThat(scanBytes("Pkg", bytes)).as("Paket %s", pkg).isNotEmpty();
    }
  }

  @Test
  @DisplayName("invertierbar_verbotene_supertypen: jeder verbotene Supertyp schlaegt als Super/Interface an")
  void invertierbar_verbotene_supertypen() throws Exception {
    for (final String type : FORBIDDEN_TYPES) {
      final byte[] asSuper = syntheticWithSuper("synthetic/Sup", type, null);
      assertThat(scanBytes("Sup", asSuper)).as("Superklasse %s", type).isNotEmpty();
      final byte[] asInterface = syntheticWithSuper("synthetic/Ifc", "java/lang/Object", type);
      assertThat(scanBytes("Ifc", asInterface)).as("Interface %s", type).isNotEmpty();
    }
  }

  @Test
  @DisplayName("invertierbar_repository_definition: @RepositoryDefinition als Klassen-Annotation")
  void invertierbar_repository_definition() throws Exception {
    final byte[] bytes =
        syntheticWithClassAnnotation(
            "synthetic/RepoDef", "Lorg/springframework/data/repository/RepositoryDefinition;");
    assertThat(scanBytes("RepoDef", bytes)).anyMatch(v -> v.contains("RepositoryDefinition"));
  }

  @Test
  @DisplayName("invertierbar_generische_signatur: Typargument aus Verbotsliste in Signature-Attribut")
  void invertierbar_generische_signatur() throws Exception {
    final byte[] bytes =
        synthetic("synthetic/Sig", cw -> cw.visitField(Opcodes.ACC_PRIVATE, "l", "Ljava/util/List;",
                "Ljava/util/List<Ljakarta/persistence/EntityManager;>;", null).visitEnd(),
            null);
    assertThat(scanBytes("Sig", bytes)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "erlaubt_param_und_namensgrenze: @Param sowie Namen mit verbotenem Praefix, aber anderer"
          + " Namensgrenze, schlagen NICHT an")
  void erlaubt_param_und_namensgrenze() throws Exception {
    final byte[] bytes =
        synthetic("synthetic/Allowed", cw -> {
              final var fv = cw.visitField(Opcodes.ACC_PRIVATE, "x", "Ljava/lang/Object;", null, null);
              fv.visitAnnotation("L" + ALLOWED_PARAM + ";", true).visitEnd();
              fv.visitEnd();
              // Namensgrenze: RepositoryDefinitionX ist weder Repository noch RepositoryDefinition
              cw.visitField(Opcodes.ACC_PRIVATE, "d",
                  "Lorg/springframework/data/repository/RepositoryDefinitionX;", null, null)
                  .visitEnd();
              cw.visitField(Opcodes.ACC_PRIVATE, "n",
                  "Lorg/springframework/data/repository/NoRepositoryBean;", null, null)
                  .visitEnd();
            },
            cw -> {
              final MethodVisitor mv =
                  cw.visitMethod(Opcodes.ACC_PUBLIC, "m", "(Ljava/lang/Object;)V", null, null);
              mv.visitParameterAnnotation(0, "L" + ALLOWED_PARAM + ";", true).visitEnd();
              mv.visitCode();
              mv.visitInsn(Opcodes.RETURN);
              mv.visitMaxs(0, 1);
              mv.visitEnd();
            });
    assertThat(scanBytes("Allowed", bytes)).as("erlaubte Referenzen").isEmpty();
  }

  @Test
  @DisplayName("invertierbar_javac_fixture: echte javac-Ausgabe (CrudRepository-Supertyp) wird erkannt")
  void invertierbar_javac_fixture() throws Exception {
    try (InputStream in =
        JpaArchitectureViolationFixture.class.getResourceAsStream(
            JpaArchitectureViolationFixture.class.getSimpleName() + ".class")) {
      assertThat(in).isNotNull();
      assertThat(scanBytes("JpaArchitectureViolationFixture", in.readAllBytes()))
          .anyMatch(v -> v.contains("CrudRepository"));
    }
  }

  // ---------------------------------------------------------------------------
  // Scan-Kern
  // ---------------------------------------------------------------------------

  private static void requireNonEmpty(final ScanResult result) {
    if (result.classCount() <= 0) {
      throw new AssertionError("Scan-Guard: keine .class-Datei gescannt (leerer Scan)");
    }
    if (!result.applicationScanned()) {
      throw new AssertionError("Scan-Guard: BrasiNovaApplication wurde nicht gescannt");
    }
  }

  private static ScanResult scanDirectory(final Path dir) {
    final List<String> violations = new ArrayList<>();
    int count = 0;
    boolean application = false;
    final String applicationEntry =
        BrasiNovaApplication.class.getName().replace('.', '/') + ".class";
    final List<Path> files;
    try (Stream<Path> walk = Files.walk(dir)) {
      files = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
    } catch (final IOException e) {
      throw new UncheckedIOException("Verzeichnis nicht lesbar: " + dir, e);
    }
    for (final Path file : files) {
      final String label = dir.relativize(file).toString().replace('\\', '/');
      final byte[] bytes;
      try {
        bytes = Files.readAllBytes(file);
      } catch (final IOException e) {
        throw new UncheckedIOException("Klasse nicht lesbar: " + file, e);
      }
      violations.addAll(scanBytes(label, bytes));
      count++;
      if (label.equals(applicationEntry)) {
        application = true;
      }
    }
    return new ScanResult(count, application, violations);
  }

  /** Scannt die Utf8-Eintraege des Constant Pools einer Klasse; Formatfehler fuehren zum Abbruch. */
  private static List<String> scanBytes(final String label, final byte[] bytes) {
    final List<String> violations = new ArrayList<>();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != 0xCAFEBABE) {
        throw new IllegalStateException("Kein Klassenformat (Magic fehlt): " + label);
      }
      in.readUnsignedShort();
      in.readUnsignedShort();
      final int count = in.readUnsignedShort();
      for (int i = 1; i < count; i++) {
        final int tag = in.readUnsignedByte();
        switch (tag) {
          case 1 -> {
            final String utf8 = in.readUTF();
            final var matcher = FORBIDDEN_PATTERN.matcher(utf8);
            if (matcher.find()) {
              violations.add(label + ": verbotene Referenz '" + utf8 + "'");
            }
          }
          case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
          case 5, 6 -> {
            in.skipNBytes(8);
            i++;
          }
          case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
          case 15 -> in.skipNBytes(3);
          default ->
              throw new IllegalStateException(
                  "Unbekannter Constant-Pool-Tag " + tag + " in " + label);
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("Klasse nicht vollstaendig lesbar: " + label, e);
    }
    return violations;
  }

  // ---------------------------------------------------------------------------
  // Synthetische Klassen (Springs repackaged ASM)
  // ---------------------------------------------------------------------------

  @FunctionalInterface
  private interface WriterStep {
    void apply(ClassWriter cw);
  }

  private static byte[] synthetic(
      final String name, final WriterStep fields, final WriterStep methods) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
    if (fields != null) {
      fields.apply(cw);
    }
    if (methods != null) {
      methods.apply(cw);
    }
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static byte[] syntheticWithClassAnnotation(final String name, final String descriptor) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
    cw.visitAnnotation(descriptor, true).visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static byte[] syntheticWithSuper(
      final String name, final String superName, final String interfaceName) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC,
        name,
        null,
        superName,
        interfaceName == null ? null : new String[] {interfaceName});
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static Path resolveProductionClassesDir() throws Exception {
    final java.net.URL location =
        BrasiNovaApplication.class.getProtectionDomain().getCodeSource().getLocation();
    return Path.of(location.toURI());
  }
}
