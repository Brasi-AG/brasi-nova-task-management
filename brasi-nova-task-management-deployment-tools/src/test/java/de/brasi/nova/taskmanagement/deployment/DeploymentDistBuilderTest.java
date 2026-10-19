package de.brasi.nova.taskmanagement.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentDistBuilderTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void parsesMavenDependencyListWithWindowsPathsAndClassifier() throws Exception {
    Path dependencyList = tempDir.resolve("dependencies.txt");
    Files.writeString(
        dependencyList,
        """

        The following files have been resolved:
           io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.124.Final:compile:C:\\Users\\stefan\\.m2\\repository\\io\\netty\\netty-transport-native-epoll\\4.1.124.Final\\netty-transport-native-epoll-4.1.124.Final-linux-x86_64.jar -- module io.netty.transport.epoll.linux.x86_64 [auto]
           io.keel.framework:ksf-web:jar:2.3.24:compile:C:\\Users\\stefan\\.m2\\repository\\io\\keel\\framework\\ksf-web\\2.3.24\\ksf-web-2.3.24.jar -- module ksf.web (auto)
           de.brasi.nova:brasi-nova-task-management-api:jar:0.0.1-SNAPSHOT:compile:C:\\Users\\stefan\\.m2\\repository\\de\\brasi\\nova\\brasi-nova-task-management-api\\0.0.1-SNAPSHOT\\brasi-nova-task-management-api-0.0.1-SNAPSHOT.jar -- module brasi.nova.taskmanagement.api (auto)
        """,
        StandardCharsets.UTF_8);

    List<DeploymentDistBuilder.MavenArtifact> artifacts =
        DeploymentDistBuilder.parseDependencyList(dependencyList);

    assertThat(artifacts).hasSize(3);
    assertThat(artifacts.getFirst().classifier()).isEqualTo("linux-x86_64");
    assertThat(artifacts.getFirst().fileName())
        .isEqualTo("netty-transport-native-epoll-4.1.124.Final-linux-x86_64.jar");
    assertThat(artifacts.get(1).classification())
        .isEqualTo(DeploymentDistBuilder.Classification.INTERNAL_DEPENDENCIES);
    assertThat(artifacts.get(2).classification())
        .isEqualTo(DeploymentDistBuilder.Classification.SNAPSHOT_DEPENDENCIES);
  }

  @Test
  void contentFingerprintUsesSortedFinalPathAndFileShaOnly() {
    DeploymentDistBuilder.DeploymentFile first =
        new DeploymentDistBuilder.DeploymentFile(
            "lib/b.jar",
            "b.jar",
            "sha-b",
            1L,
            DeploymentDistBuilder.Classification.THIRD_PARTY_DEPENDENCIES,
            null);
    DeploymentDistBuilder.DeploymentFile second =
        new DeploymentDistBuilder.DeploymentFile(
            "lib/a.jar",
            "a.jar",
            "sha-a",
            1L,
            DeploymentDistBuilder.Classification.THIRD_PARTY_DEPENDENCIES,
            null);

    assertThat(DeploymentDistBuilder.contentFingerprint(List.of(first, second)))
        .isEqualTo(DeploymentDistBuilder.contentFingerprint(List.of(second, first)));
  }

  @Test
  void bundleFingerprintIgnoresVolatileDependencyJarManifestAttributes() throws Exception {
    Path dependencyList = tempDir.resolve("dependencies.txt");
    Files.writeString(
        dependencyList,
        """
        The following files have been resolved:
           de.brasi.nova:brasi-nova-task-management-api:jar:0.0.1-SNAPSHOT:compile:C:\\repo\\brasi-nova-task-management-api-0.0.1-SNAPSHOT.jar
        """,
        StandardCharsets.UTF_8);

    Path firstExtracted = tempDir.resolve("first-extracted");
    Path secondExtracted = tempDir.resolve("second-extracted");
    Files.createDirectories(firstExtracted.resolve("lib"));
    Files.createDirectories(secondExtracted.resolve("lib"));
    createThinJar(firstExtracted.resolve("brasi-nova-task-management-rt-0.0.1-SNAPSHOT.jar"));
    createThinJar(secondExtracted.resolve("brasi-nova-task-management-rt-0.0.1-SNAPSHOT.jar"));
    createDependencyJar(
        firstExtracted.resolve("lib/brasi-nova-task-management-api-0.0.1-SNAPSHOT.jar"),
        "Maven JAR Plugin 3.4.1",
        true);
    createDependencyJar(
        secondExtracted.resolve("lib/brasi-nova-task-management-api-0.0.1-SNAPSHOT.jar"),
        "Maven JAR Plugin 3.5.0",
        false);

    DeploymentDistBuilder.buildFromExtracted(
        firstExtracted, dependencyList, tempDir.resolve("first-output"));
    DeploymentDistBuilder.buildFromExtracted(
        secondExtracted, dependencyList, tempDir.resolve("second-output"));

    JsonNode firstManifest =
        JSON.readTree(tempDir.resolve("first-output/deployment-manifest.json").toFile());
    JsonNode secondManifest =
        JSON.readTree(tempDir.resolve("second-output/deployment-manifest.json").toFile());

    assertThat(bundleFor(firstManifest, "snapshot-dependencies").get("contentSha256").asText())
        .isEqualTo(bundleFor(secondManifest, "snapshot-dependencies").get("contentSha256").asText());
  }

  @Test
  void internalBundleFingerprintIgnoresVolatileDependencyJarManifestAttributes() throws Exception {
    Path dependencyList = tempDir.resolve("dependencies.txt");
    Files.writeString(
        dependencyList,
        """
        The following files have been resolved:
           de.brasi.nova:brasi-nova-task-management-entities:jar:0.2.0:compile:C:\\repo\\brasi-nova-task-management-entities-0.2.0.jar
        """,
        StandardCharsets.UTF_8);

    Path firstExtracted = tempDir.resolve("first-internal-extracted");
    Path secondExtracted = tempDir.resolve("second-internal-extracted");
    Files.createDirectories(firstExtracted.resolve("lib"));
    Files.createDirectories(secondExtracted.resolve("lib"));
    createThinJar(firstExtracted.resolve("brasi-nova-task-management-rt-0.0.1-SNAPSHOT.jar"));
    createThinJar(secondExtracted.resolve("brasi-nova-task-management-rt-0.0.1-SNAPSHOT.jar"));
    createDependencyJar(
        firstExtracted.resolve("lib/brasi-nova-task-management-entities-0.2.0.jar"),
        "Maven JAR Plugin 3.4.1",
        true);
    createDependencyJar(
        secondExtracted.resolve("lib/brasi-nova-task-management-entities-0.2.0.jar"),
        "Maven JAR Plugin 3.5.0",
        false);

    DeploymentDistBuilder.buildFromExtracted(
        firstExtracted, dependencyList, tempDir.resolve("first-internal-output"));
    DeploymentDistBuilder.buildFromExtracted(
        secondExtracted, dependencyList, tempDir.resolve("second-internal-output"));

    JsonNode firstManifest =
        JSON.readTree(tempDir.resolve("first-internal-output/deployment-manifest.json").toFile());
    JsonNode secondManifest =
        JSON.readTree(tempDir.resolve("second-internal-output/deployment-manifest.json").toFile());

    assertThat(bundleFor(firstManifest, "internal-dependencies").get("contentSha256").asText())
        .isEqualTo(bundleFor(secondManifest, "internal-dependencies").get("contentSha256").asText());
  }

  @Test
  void buildsGroupedDeploymentDistAndManifestFromExtractedThinJar() throws Exception {
    Path extracted = tempDir.resolve("extracted");
    Path lib = extracted.resolve("lib");
    Files.createDirectories(lib);
    createThinJar(extracted.resolve("brasi-nova-task-management-rt-0.0.1-SNAPSHOT.jar"));
    createDependencyJar(
        lib.resolve("brasi-nova-task-management-api-0.0.1-SNAPSHOT.jar"), "Test", false);
    createDependencyJar(lib.resolve("ksf-web-2.3.24.jar"), "Test", false);
    createDependencyJar(lib.resolve("spring-core-6.2.10.jar"), "Test", false);
    createDependencyJar(lib.resolve("spring-boot-jarmode-tools-3.5.5.jar"), "Test", false);

    Path dependencyList = tempDir.resolve("dependencies.txt");
    Files.writeString(
        dependencyList,
        """
        The following files have been resolved:
           de.brasi.nova:brasi-nova-task-management-api:jar:0.0.1-SNAPSHOT:compile:C:\\repo\\brasi-nova-task-management-api-0.0.1-SNAPSHOT.jar
           io.keel.framework:ksf-web:jar:2.3.24:compile:C:\\repo\\ksf-web-2.3.24.jar
           org.springframework:spring-core:jar:6.2.10:compile:C:\\repo\\spring-core-6.2.10.jar
        """,
        StandardCharsets.UTF_8);

    Path output = tempDir.resolve("deployment-dist");
    DeploymentDistBuilder.buildFromExtracted(extracted, dependencyList, output);

    JsonNode manifest = JSON.readTree(output.resolve("deployment-manifest.json").toFile());
    assertThat(manifest.get("applicationArtifact").get("path").asText())
        .isEqualTo("application/brasi-nova-task-management-rt-0.0.1-SNAPSHOT.jar");
    assertRelocatedClassPath(
        output.resolve("application/brasi-nova-task-management-rt-0.0.1-SNAPSHOT.jar"));
    assertThat(manifest.get("files")).hasSize(5);
    assertThat(
            fileFor(manifest, "brasi-nova-task-management-api-0.0.1-SNAPSHOT.jar")
                .get("sha256")
                .asText())
        .isNotBlank();
    assertThat(bundleClasses(manifest))
        .containsExactlyInAnyOrder(
            "snapshot-dependencies", "internal-dependencies", "third-party-dependencies");

    JsonNode snapshotBundle = bundleFor(manifest, "snapshot-dependencies");
    assertThat(snapshotBundle.get("assetName").asText())
        .startsWith("snapshot-dependencies-")
        .endsWith(".zip");
    assertThat(snapshotBundle.get("release").asText()).isEqualTo("deployment-content-store");
    assertThat(snapshotBundle.get("artifactSha256").asText())
        .isEqualTo(snapshotBundle.get("sha256").asText());
    assertZipContains(
        output.resolve(snapshotBundle.get("path").asText()),
        "lib/brasi-nova-task-management-api-0.0.1-SNAPSHOT.jar");
    assertThat(manifest.get("files").findValuesAsText("fileName"))
        .contains("spring-boot-jarmode-tools-3.5.5.jar");
    assertThat(output.resolve("deployment.zip")).doesNotExist();
  }

  @Test
  void plansOnlyChangedApplicationAndBundlesForDownload() {
    DeploymentDistBuilder.Manifest previous =
        DeploymentDistBuilder.Manifest.synthetic(
            "app-old", "snapshot-old", "internal-old", "third-party-old");

    assertThat(
            DeploymentDistBuilder.planDownloads(
                previous,
                DeploymentDistBuilder.Manifest.synthetic(
                    "app-new", "snapshot-old", "internal-old", "third-party-old")))
        .containsExactly("application");

    assertThat(
            DeploymentDistBuilder.planDownloads(
                previous,
                DeploymentDistBuilder.Manifest.synthetic(
                    "app-new", "snapshot-new", "internal-old", "third-party-old")))
        .containsExactly("application", "snapshot-dependencies");

    assertThat(
            DeploymentDistBuilder.planDownloads(
                previous,
                DeploymentDistBuilder.Manifest.synthetic(
                    "app-old", "snapshot-old", "internal-new", "third-party-old")))
        .containsExactly("internal-dependencies");

    assertThat(
            DeploymentDistBuilder.planDownloads(
                previous,
                DeploymentDistBuilder.Manifest.synthetic(
                    "app-old", "snapshot-old", "internal-old", "third-party-new")))
        .containsExactly("third-party-dependencies");
  }

  private static Set<String> bundleClasses(JsonNode manifest) {
    return JSON.convertValue(
        manifest.get("bundles").findValuesAsText("classification"),
        JSON.getTypeFactory().constructCollectionType(Set.class, String.class));
  }

  private static JsonNode bundleFor(JsonNode manifest, String classification) {
    for (JsonNode bundle : manifest.get("bundles")) {
      if (classification.equals(bundle.get("classification").asText())) {
        return bundle;
      }
    }
    throw new AssertionError("Missing bundle " + classification);
  }

  private static JsonNode fileFor(JsonNode manifest, String fileName) {
    for (JsonNode file : manifest.get("files")) {
      if (fileName.equals(file.get("fileName").asText())) {
        return file;
      }
    }
    throw new AssertionError("Missing file " + fileName);
  }

  private static void assertZipContains(Path zip, String entry) throws Exception {
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      assertThat(zipFile.getEntry(entry)).isNotNull();
    }
  }

  private static void createThinJar(Path jar) throws Exception {
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.put(Attributes.Name.MAIN_CLASS, "example.Main");
    attributes.put(Attributes.Name.CLASS_PATH, "lib/a.jar lib/b.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      output.putNextEntry(new ZipEntry("example/Main.class"));
      output.write(new byte[] {0});
      output.closeEntry();
    }
  }

  private static void createDependencyJar(Path jar, String createdBy, boolean includeJavaVersion)
      throws Exception {
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.putValue("Created-By", createdBy);
    attributes.putValue("Build-Jdk-Spec", "25");
    if (includeJavaVersion) {
      attributes.putValue("Java-Version", "25");
    }
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      output.putNextEntry(new ZipEntry("example/Dependency.class"));
      output.write(new byte[] {1});
      output.closeEntry();
    }
  }

  private static void assertRelocatedClassPath(Path jar) throws Exception {
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      String classPath =
          jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
      assertThat(classPath).isEqualTo("../lib/a.jar ../lib/b.jar");
    }
  }
}
