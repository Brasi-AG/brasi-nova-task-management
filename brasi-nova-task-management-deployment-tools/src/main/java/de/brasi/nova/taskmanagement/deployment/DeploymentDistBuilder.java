package de.brasi.nova.taskmanagement.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DeploymentDistBuilder {

  private static final Pattern DEPENDENCY_LIST_LINE =
      Pattern.compile(
          "^\\s*([^:]+):([^:]+):([^:]+):(?:(.*?):)?([^:]+):(compile|runtime|provided|test|system|import):(.+?)(?:\\s+--\\s+.*)?$");
  private static final FileTime ZIP_ENTRY_TIME =
      FileTime.from(Instant.parse("1980-01-01T00:00:00Z"));
  static final String CONTENT_STORE_RELEASE = "deployment-content-store";
  private static final java.util.Set<String> VOLATILE_MANIFEST_ATTRIBUTES =
      java.util.Set.of("Build-Jdk-Spec", "Created-By", "Java-Version");

  private DeploymentDistBuilder() {}

  public static void main(String[] args) throws Exception {
    CliArguments cli = CliArguments.parse(args);
    Path extracted = cli.workDir().resolve("extracted");
    Files.createDirectories(cli.workDir());
    recreateDirectory(extracted);
    extractWithSpringBootTools(cli.javaExecutable(), cli.fatJar(), extracted);
    buildFromExtracted(extracted, cli.dependencyList(), cli.outputDir());
  }

  static List<MavenArtifact> parseDependencyList(Path dependencyList) throws IOException {
    List<MavenArtifact> artifacts = new ArrayList<>();
    for (String line : Files.readAllLines(dependencyList, StandardCharsets.UTF_8)) {
      Matcher matcher = DEPENDENCY_LIST_LINE.matcher(line);
      if (!matcher.matches()) {
        continue;
      }
      artifacts.add(
          new MavenArtifact(
              matcher.group(1),
              matcher.group(2),
              matcher.group(3),
              emptyToNull(matcher.group(4)),
              matcher.group(5),
              matcher.group(6),
              artifactFileName(matcher.group(7))));
    }
    if (artifacts.isEmpty()) {
      throw new IllegalStateException("No runtime dependencies parsed from " + dependencyList);
    }
    return artifacts;
  }

  static Manifest buildFromExtracted(Path extracted, Path dependencyList, Path outputDir)
      throws IOException {
    Path libDir = extracted.resolve("lib");
    if (!Files.isDirectory(libDir)) {
      throw new IllegalStateException("Extracted Spring Boot tools output has no lib directory: " + libDir);
    }

    recreateDirectory(outputDir);
    Files.createDirectories(outputDir.resolve("application"));
    Files.createDirectories(outputDir.resolve("bundles"));

    Path thinJar = findThinJar(extracted);
    DeploymentFile applicationFile =
        copyApplicationArtifact(thinJar, outputDir.resolve("application").resolve(thinJar.getFileName()));

    Map<String, MavenArtifact> artifactsByFile =
        parseDependencyList(dependencyList).stream()
            .collect(Collectors.toMap(MavenArtifact::fileName, artifact -> artifact, (left, right) -> left));

    List<DeploymentFile> libraryFiles = new ArrayList<>();
    try (Stream<Path> files = Files.list(libDir)) {
      for (Path lib : files.filter(Files::isRegularFile).sorted().toList()) {
        String fileName = lib.getFileName().toString();
        MavenArtifact artifact =
            Optional.ofNullable(artifactsByFile.get(fileName))
                .or(() -> springBootJarmodeToolsArtifact(fileName))
                .orElse(null);
        if (artifact == null) {
          throw new IllegalStateException(
              "No Maven coordinates found for extracted library " + fileName);
        }
        String fileSha256 =
            artifact.classification() == Classification.THIRD_PARTY_DEPENDENCIES
                ? sha256(lib)
                : deploymentSha256(lib);
        libraryFiles.add(
            new DeploymentFile(
                "lib/" + fileName,
                fileName,
                fileSha256,
                fileSha256,
                Files.size(lib),
                artifact.classification(),
                artifact)
                .withSourcePath(lib));
      }
    }

    List<BundleArtifact> bundles = writeBundles(outputDir, libraryFiles);
    List<DeploymentFile> allFiles = new ArrayList<>();
    allFiles.add(applicationFile);
    allFiles.addAll(libraryFiles);

    Manifest manifest = new Manifest(1, applicationFile, bundles, allFiles);
    Files.writeString(
        outputDir.resolve("deployment-manifest.json"),
        toJson(manifest.toJsonMap()) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    return manifest;
  }

  static String contentFingerprint(List<DeploymentFile> files) {
    String payload =
        files.stream()
            .sorted(Comparator.comparing(DeploymentFile::path))
            .map(file -> file.path() + "\t" + file.contentSha256())
            .collect(Collectors.joining("\n", "", "\n"));
    return sha256(payload.getBytes(StandardCharsets.UTF_8));
  }

  static List<String> planDownloads(Manifest previous, Manifest next) {
    List<String> downloads = new ArrayList<>();
    if (!Objects.equals(
        previous.applicationArtifact().sha256(), next.applicationArtifact().sha256())) {
      downloads.add("application");
    }

    Map<Classification, BundleArtifact> previousBundles = previous.bundlesByClassification();
    for (BundleArtifact bundle : next.bundles()) {
      BundleArtifact old = previousBundles.get(bundle.classification());
      if (old == null || !Objects.equals(old.contentSha256(), bundle.contentSha256())) {
        downloads.add(bundle.classification().id());
      }
    }
    return downloads;
  }

  private static void extractWithSpringBootTools(String javaExecutable, Path fatJar, Path extracted)
      throws IOException, InterruptedException {
    List<String> command =
        List.of(
            javaExecutable,
            "-Djarmode=tools",
            "-jar",
            fatJar.toAbsolutePath().toString(),
            "extract",
            "--destination",
            extracted.toAbsolutePath().toString());
    Process process = new ProcessBuilder(command).inheritIO().start();
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException("Spring Boot tools extract failed with exit code " + exitCode);
    }
  }

  private static DeploymentFile copyApplicationArtifact(Path source, Path destination)
      throws IOException {
    copyThinJarWithRelocatedClassPath(source, destination);
    return new DeploymentFile(
        "application/" + destination.getFileName(),
        destination.getFileName().toString(),
        sha256(destination),
        Files.size(destination),
        Classification.APPLICATION,
        null);
  }

  private static void copyThinJarWithRelocatedClassPath(Path source, Path destination)
      throws IOException {
    Files.createDirectories(destination.getParent());
    try (JarFile sourceJar = new JarFile(source.toFile())) {
      java.util.jar.Manifest manifest = new java.util.jar.Manifest(sourceJar.getManifest());
      Attributes attributes = manifest.getMainAttributes();
      String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
      if (classPath != null && !classPath.isBlank()) {
        attributes.put(Attributes.Name.CLASS_PATH, relocateClassPath(classPath));
      }
      try (JarOutputStream output =
          new JarOutputStream(Files.newOutputStream(destination), manifest)) {
        List<JarEntry> entries =
            sourceJar.stream()
                .filter(entry -> !entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF"))
                .sorted(Comparator.comparing(JarEntry::getName))
                .toList();
        for (JarEntry sourceEntry : entries) {
          JarEntry targetEntry = new JarEntry(sourceEntry.getName());
          targetEntry.setLastModifiedTime(ZIP_ENTRY_TIME);
          targetEntry.setLastAccessTime(ZIP_ENTRY_TIME);
          targetEntry.setCreationTime(ZIP_ENTRY_TIME);
          output.putNextEntry(targetEntry);
          if (!sourceEntry.isDirectory()) {
            try (InputStream input = sourceJar.getInputStream(sourceEntry)) {
              input.transferTo(output);
            }
          }
          output.closeEntry();
        }
      }
    }
  }

  private static String relocateClassPath(String classPath) {
    return Stream.of(classPath.split("\\s+"))
        .filter(entry -> !entry.isBlank())
        .map(entry -> entry.startsWith("lib/") ? "../" + entry : entry)
        .collect(Collectors.joining(" "));
  }

  private static List<BundleArtifact> writeBundles(Path outputDir, List<DeploymentFile> libraryFiles)
      throws IOException {
    Map<Classification, List<DeploymentFile>> grouped = new EnumMap<>(Classification.class);
    grouped.put(Classification.SNAPSHOT_DEPENDENCIES, new ArrayList<>());
    grouped.put(Classification.INTERNAL_DEPENDENCIES, new ArrayList<>());
    grouped.put(Classification.THIRD_PARTY_DEPENDENCIES, new ArrayList<>());
    for (DeploymentFile file : libraryFiles) {
      grouped.computeIfAbsent(file.classification(), ignored -> new ArrayList<>()).add(file);
    }

    List<BundleArtifact> bundles = new ArrayList<>();
    for (Classification classification :
        List.of(
            Classification.SNAPSHOT_DEPENDENCIES,
            Classification.INTERNAL_DEPENDENCIES,
            Classification.THIRD_PARTY_DEPENDENCIES)) {
      List<DeploymentFile> files = grouped.getOrDefault(classification, List.of()).stream()
          .sorted(Comparator.comparing(DeploymentFile::path))
          .toList();
      String contentSha256 = contentFingerprint(files);
      String fileName = classification.id() + "-" + contentSha256 + ".zip";
      Path bundle = outputDir.resolve("bundles").resolve(fileName);
      writeBundle(bundle, files);
      bundles.add(
          new BundleArtifact(
              classification,
              "bundles/" + fileName,
              fileName,
              contentSha256,
              sha256(bundle),
              Files.size(bundle),
              files.size(),
              files.stream().mapToLong(DeploymentFile::size).sum()));
    }
    return bundles;
  }

  private static void writeBundle(Path bundle, List<DeploymentFile> files) throws IOException {
    Files.createDirectories(bundle.getParent());
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
      for (DeploymentFile file : files) {
        Path source = file.source().orElseThrow();
        ZipEntry entry = new ZipEntry(file.path());
        entry.setLastModifiedTime(ZIP_ENTRY_TIME);
        entry.setLastAccessTime(ZIP_ENTRY_TIME);
        entry.setCreationTime(ZIP_ENTRY_TIME);
        zip.putNextEntry(entry);
        Files.copy(source, zip);
        zip.closeEntry();
      }
    }
  }

  private static Path findThinJar(Path extracted) throws IOException {
    try (Stream<Path> jars = Files.list(extracted)) {
      List<Path> candidates =
          jars.filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".jar"))
              .sorted()
              .toList();
      if (candidates.size() != 1) {
        throw new IllegalStateException(
            "Expected exactly one extracted thin application jar, found " + candidates.size());
      }
      return candidates.getFirst();
    }
  }

  private static Optional<MavenArtifact> springBootJarmodeToolsArtifact(String fileName) {
    Pattern pattern = Pattern.compile("^spring-boot-jarmode-tools-(.+)\\.jar$");
    Matcher matcher = pattern.matcher(fileName);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(
        new MavenArtifact(
            "org.springframework.boot",
            "spring-boot-jarmode-tools",
            "jar",
            null,
            matcher.group(1),
            "plugin",
            fileName));
  }

  private static void recreateDirectory(Path directory) throws IOException {
    if (Files.exists(directory)) {
      Files.walkFileTree(
          directory,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              if (exc != null) {
                throw exc;
              }
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    }
    Files.createDirectories(directory);
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest = sha256Digest();
    try (InputStream input = Files.newInputStream(file);
        DigestInputStream ignored = new DigestInputStream(input, digest)) {
      ignored.transferTo(OutputStream.nullOutputStream());
    }
    return hex(digest.digest());
  }

  private static String deploymentSha256(Path file) throws IOException {
    if (file.getFileName().toString().endsWith(".jar")) {
      return jarContentSha256(file);
    }
    return sha256(file);
  }

  private static String jarContentSha256(Path file) throws IOException {
    MessageDigest digest = sha256Digest();
    try (JarFile jarFile = new JarFile(file.toFile())) {
      List<JarEntry> entries =
          jarFile.stream()
              .filter(entry -> !entry.isDirectory())
              .sorted(Comparator.comparing(JarEntry::getName))
              .toList();
      for (JarEntry entry : entries) {
        updateDigest(digest, entry.getName());
        updateDigest(digest, "\t");
        try (InputStream input = jarFile.getInputStream(entry)) {
          if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName())) {
            updateManifestDigest(digest, input);
          } else {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
              digest.update(buffer, 0, read);
            }
          }
        }
        updateDigest(digest, "\n");
      }
    }
    return hex(digest.digest());
  }

  private static void updateManifestDigest(MessageDigest digest, InputStream input)
      throws IOException {
    java.util.jar.Manifest manifest = new java.util.jar.Manifest(input);
    updateAttributesDigest(digest, "main", manifest.getMainAttributes());
    for (Map.Entry<String, Attributes> entry :
        manifest.getEntries().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .toList()) {
      updateAttributesDigest(digest, entry.getKey(), entry.getValue());
    }
  }

  private static void updateAttributesDigest(
      MessageDigest digest, String section, Attributes attributes) {
    updateDigest(digest, section);
    updateDigest(digest, "\n");
    attributes.entrySet().stream()
        .map(entry -> Map.entry(entry.getKey().toString(), entry.getValue().toString()))
        .filter(entry -> !VOLATILE_MANIFEST_ATTRIBUTES.contains(entry.getKey()))
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              updateDigest(digest, entry.getKey());
              updateDigest(digest, ": ");
              updateDigest(digest, entry.getValue());
              updateDigest(digest, "\n");
            });
  }

  private static void updateDigest(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] bytes) {
    MessageDigest digest = sha256Digest();
    digest.update(bytes);
    return hex(digest.digest());
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      builder.append(String.format(Locale.ROOT, "%02x", value));
    }
    return builder.toString();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String artifactFileName(String artifactPath) {
    String normalized = artifactPath.trim().replace('\\', '/');
    int lastSeparator = normalized.lastIndexOf('/');
    return lastSeparator >= 0 ? normalized.substring(lastSeparator + 1) : normalized;
  }

  private static String toJson(Object value) {
    StringBuilder builder = new StringBuilder();
    appendJson(builder, value, 0);
    return builder.toString();
  }

  private static void appendJson(StringBuilder builder, Object value, int indent) {
    if (value == null) {
      builder.append("null");
    } else if (value instanceof String text) {
      builder.append('"').append(escapeJson(text)).append('"');
    } else if (value instanceof Number || value instanceof Boolean) {
      builder.append(value);
    } else if (value instanceof Map<?, ?> map) {
      appendJsonObject(builder, map, indent);
    } else if (value instanceof Iterable<?> iterable) {
      appendJsonArray(builder, iterable, indent);
    } else {
      throw new IllegalArgumentException("Unsupported JSON value " + value.getClass());
    }
  }

  private static void appendJsonObject(StringBuilder builder, Map<?, ?> map, int indent) {
    builder.append('{');
    if (!map.isEmpty()) {
      int nextIndent = indent + 2;
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          builder.append(',');
        }
        first = false;
        builder.append('\n').append(" ".repeat(nextIndent));
        appendJson(builder, entry.getKey().toString(), nextIndent);
        builder.append(": ");
        appendJson(builder, entry.getValue(), nextIndent);
      }
      builder.append('\n').append(" ".repeat(indent));
    }
    builder.append('}');
  }

  private static void appendJsonArray(StringBuilder builder, Iterable<?> iterable, int indent) {
    builder.append('[');
    int nextIndent = indent + 2;
    boolean first = true;
    for (Object item : iterable) {
      if (!first) {
        builder.append(',');
      }
      first = false;
      builder.append('\n').append(" ".repeat(nextIndent));
      appendJson(builder, item, nextIndent);
    }
    if (!first) {
      builder.append('\n').append(" ".repeat(indent));
    }
    builder.append(']');
  }

  private static String escapeJson(String value) {
    StringBuilder builder = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (ch < 0x20) {
            builder.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
          } else {
            builder.append(ch);
          }
        }
      }
    }
    return builder.toString();
  }

  public enum Classification {
    APPLICATION("application"),
    SNAPSHOT_DEPENDENCIES("snapshot-dependencies"),
    INTERNAL_DEPENDENCIES("internal-dependencies"),
    THIRD_PARTY_DEPENDENCIES("third-party-dependencies");

    private final String id;

    Classification(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  public record MavenArtifact(
      String groupId,
      String artifactId,
      String type,
      String classifier,
      String version,
      String scope,
      String fileName) {

    Classification classification() {
      if (version.endsWith("SNAPSHOT")) {
        return Classification.SNAPSHOT_DEPENDENCIES;
      }
      if (groupId.equals("de.brasi.nova")
          || groupId.startsWith("de.brasi.nova.")
          || groupId.equals("io.keel.framework")
          || groupId.startsWith("io.keel.framework.")) {
        return Classification.INTERNAL_DEPENDENCIES;
      }
      return Classification.THIRD_PARTY_DEPENDENCIES;
    }

    Map<String, Object> toJsonMap() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("groupId", groupId);
      json.put("artifactId", artifactId);
      json.put("type", type);
      if (classifier != null) {
        json.put("classifier", classifier);
      }
      json.put("version", version);
      json.put("scope", scope);
      return json;
    }
  }

  public record DeploymentFile(
      String path,
      String fileName,
      String sha256,
      String contentSha256,
      long size,
      Classification classification,
      MavenArtifact mavenArtifact,
      Path sourcePath) {

    public DeploymentFile(
        String path,
        String fileName,
        String sha256,
        long size,
        Classification classification,
        MavenArtifact mavenArtifact) {
      this(path, fileName, sha256, sha256, size, classification, mavenArtifact, null);
    }

    public DeploymentFile(
        String path,
        String fileName,
        String sha256,
        String contentSha256,
        long size,
        Classification classification,
        MavenArtifact mavenArtifact) {
      this(path, fileName, sha256, contentSha256, size, classification, mavenArtifact, null);
    }

    Optional<Path> source() {
      return Optional.ofNullable(sourcePath);
    }

    DeploymentFile withSourcePath(Path sourcePath) {
      return new DeploymentFile(
          path, fileName, sha256, contentSha256, size, classification, mavenArtifact, sourcePath);
    }

    Map<String, Object> toJsonMap() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("path", path);
      json.put("fileName", fileName);
      json.put("sha256", sha256);
      json.put("size", size);
      json.put("classification", classification.id());
      json.put("layer", classification.id());
      if (mavenArtifact != null) {
        json.put("maven", mavenArtifact.toJsonMap());
      }
      return json;
    }
  }

  public record BundleArtifact(
      Classification classification,
      String path,
      String fileName,
      String contentSha256,
      String sha256,
      long size,
      int fileCount,
      long uncompressedSize,
      String assetName,
      String artifactSha256,
      String release) {

    public BundleArtifact(
        Classification classification,
        String path,
        String fileName,
        String contentSha256,
        String sha256,
        long size,
        int fileCount,
        long uncompressedSize) {
      this(
          classification,
          path,
          fileName,
          contentSha256,
          sha256,
          size,
          fileCount,
          uncompressedSize,
          fileName,
          sha256,
          CONTENT_STORE_RELEASE);
    }

    Map<String, Object> toJsonMap() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("classification", classification.id());
      json.put("layer", classification.id());
      json.put("path", path);
      json.put("fileName", fileName);
      json.put("assetName", assetName);
      json.put("contentSha256", contentSha256);
      json.put("sha256", sha256);
      json.put("artifactSha256", artifactSha256);
      json.put("release", release);
      json.put("size", size);
      json.put("fileCount", fileCount);
      json.put("uncompressedSize", uncompressedSize);
      return json;
    }
  }

  public record Manifest(
      int schemaVersion,
      DeploymentFile applicationArtifact,
      List<BundleArtifact> bundles,
      List<DeploymentFile> files) {

    static Manifest synthetic(
        String applicationSha,
        String snapshotSha,
        String internalSha,
        String thirdPartySha) {
      DeploymentFile application =
          new DeploymentFile(
              "application/app.jar",
              "app.jar",
              applicationSha,
              1L,
              Classification.APPLICATION,
              null);
      return new Manifest(
          1,
          application,
          List.of(
              syntheticBundle(Classification.SNAPSHOT_DEPENDENCIES, snapshotSha),
              syntheticBundle(Classification.INTERNAL_DEPENDENCIES, internalSha),
              syntheticBundle(Classification.THIRD_PARTY_DEPENDENCIES, thirdPartySha)),
          List.of(application));
    }

    private static BundleArtifact syntheticBundle(Classification classification, String contentSha) {
      return new BundleArtifact(
          classification,
          "bundles/" + classification.id() + "-" + contentSha + ".zip",
          classification.id() + "-" + contentSha + ".zip",
          contentSha,
          contentSha,
          1L,
          1,
          1L);
    }

    Map<Classification, BundleArtifact> bundlesByClassification() {
      return bundles.stream()
          .collect(Collectors.toMap(BundleArtifact::classification, bundle -> bundle));
    }

    Map<String, Object> toJsonMap() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("schemaVersion", schemaVersion);
      json.put("applicationArtifact", applicationArtifact.toJsonMap());
      json.put("bundles", bundles.stream().map(BundleArtifact::toJsonMap).toList());
      json.put("files", files.stream().map(DeploymentFile::toJsonMap).toList());
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("fileCount", files.size());
      summary.put("totalSize", files.stream().mapToLong(DeploymentFile::size).sum());
      json.put("summary", summary);
      return json;
    }
  }

  private record CliArguments(
      Path fatJar, Path dependencyList, Path outputDir, Path workDir, String javaExecutable) {

    static CliArguments parse(String[] args) {
      Map<String, String> values = new LinkedHashMap<>();
      for (int i = 0; i < args.length; i += 2) {
        if (i + 1 >= args.length || !args[i].startsWith("--")) {
          throw new IllegalArgumentException(
              "Expected --fat-jar, --dependency-list, --output-dir, --work-dir and optional --java");
        }
        values.put(args[i], args[i + 1]);
      }
      return new CliArguments(
          requiredPath(values, "--fat-jar"),
          requiredPath(values, "--dependency-list"),
          requiredPath(values, "--output-dir"),
          requiredPath(values, "--work-dir"),
          values.getOrDefault("--java", "java"));
    }

    private static Path requiredPath(Map<String, String> values, String key) {
      String value = values.get(key);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Missing " + key);
      }
      return Path.of(value);
    }
  }
}
