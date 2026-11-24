param(
  [Parameter(Mandatory = $true)]
  [string]$DeploymentRoot,

  [string]$AssetsDirectory,
  [string]$Repository,
  [string]$Tag,
  [string]$ServiceName,
  [string]$WinSwXmlPath,
  [string]$HealthUrl,
  [int]$HealthTimeoutSeconds = 120,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Get-Sha256([string]$Path) {
  (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Add-HashText([System.Security.Cryptography.HashAlgorithm]$Digest, [string]$Text) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
  if ($bytes.Length -gt 0) {
    $null = $Digest.TransformBlock($bytes, 0, $bytes.Length, $bytes, 0)
  }
}

function Add-HashStream([System.Security.Cryptography.HashAlgorithm]$Digest, [System.IO.Stream]$Stream) {
  $buffer = New-Object byte[] 8192
  while (($read = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
    $null = $Digest.TransformBlock($buffer, 0, $read, $buffer, 0)
  }
}

function Add-ContentText([System.IO.Stream]$Output, [string]$Text) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
  if ($bytes.Length -gt 0) {
    $Output.Write($bytes, 0, $bytes.Length)
  }
}

function Add-ContentStream([System.IO.Stream]$Output, [System.IO.Stream]$Stream) {
  $buffer = New-Object byte[] 8192
  while (($read = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
    $Output.Write($buffer, 0, $read)
  }
}

function Sort-Ordinal([string[]]$Values) {
  $items = [string[]]@($Values)
  [Array]::Sort($items, [StringComparer]::Ordinal)
  return $items
}

function Add-NormalizedManifestContent([System.IO.Stream]$Output, [System.IO.Stream]$Stream) {
  $reader = New-Object System.IO.StreamReader($Stream, [System.Text.Encoding]::UTF8)
  try {
    $rawLines = $reader.ReadToEnd() -split "\r?\n"
  } finally {
    $reader.Dispose()
  }
  $logicalLines = New-Object System.Collections.Generic.List[string]
  foreach ($line in $rawLines) {
    $line = $line.TrimEnd([char]13)
    if ($line.StartsWith(" ") -and $logicalLines.Count -gt 0) {
      $logicalLines[$logicalLines.Count - 1] = $logicalLines[$logicalLines.Count - 1] + $line.Substring(1)
    } elseif (-not [string]::IsNullOrWhiteSpace($line)) {
      $logicalLines.Add($line)
    }
  }
  $sections = @{}
  $sections["main"] = New-Object System.Collections.Generic.List[string]
  $currentSection = "main"
  foreach ($line in $logicalLines) {
    $separator = $line.IndexOf(":")
    if ($separator -lt 0) { continue }
    $name = $line.Substring(0, $separator)
    $value = $line.Substring($separator + 1).TrimStart()
    if ($name -eq "Name") {
      $currentSection = $value
      if (-not $sections.ContainsKey($currentSection)) {
        $sections[$currentSection] = New-Object System.Collections.Generic.List[string]
      }
      continue
    }
    $sections[$currentSection].Add($name + ": " + $value)
  }
  $volatile = @("Build-Jdk-Spec", "Created-By", "Java-Version")
  foreach ($sectionName in (@("main") + (Sort-Ordinal ([string[]]@($sections.Keys | Where-Object { $_ -ne "main" }))))) {
    Add-ContentText $Output $sectionName
    Add-ContentText $Output "`n"
    foreach ($line in (Sort-Ordinal ([string[]]@($sections[$sectionName])))) {
      $separator = $line.IndexOf(":")
      if ($separator -lt 0) { continue }
      $name = $line.Substring(0, $separator)
      if ($volatile -contains $name) { continue }
      Add-ContentText $Output $line
      Add-ContentText $Output "`n"
    }
  }
}

function Get-JarContentSha256([string]$Path) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $payload = New-Object System.IO.MemoryStream
  $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
  try {
    $entries = New-Object 'System.Collections.Generic.List[System.IO.Compression.ZipArchiveEntry]'
    foreach ($entry in $archive.Entries) {
      if (-not $entry.FullName.EndsWith("/")) {
        $entries.Add($entry)
      }
    }
    $entries.Sort([System.Comparison[System.IO.Compression.ZipArchiveEntry]]{
      param($Left, $Right)
      [StringComparer]::Ordinal.Compare($Left.FullName, $Right.FullName)
    })
    foreach ($entry in $entries) {
      Add-ContentText $payload $entry.FullName
      Add-ContentText $payload "`t"
      $stream = $entry.Open()
      try {
        if ($entry.FullName -ieq "META-INF/MANIFEST.MF") {
          Add-NormalizedManifestContent $payload $stream
        } else {
          Add-ContentStream $payload $stream
        }
      } finally {
        $stream.Dispose()
      }
      Add-ContentText $payload "`n"
    }
    $digest = [System.Security.Cryptography.SHA256]::Create()
    $hash = $digest.ComputeHash($payload.ToArray())
    return ([BitConverter]::ToString($digest.Hash) -replace "-", "").ToLowerInvariant()
  } finally {
    if ($digest) { $digest.Dispose() }
    $archive.Dispose()
    $payload.Dispose()
  }
}

function Get-DeploymentSha256([string]$Path, [string]$Classification) {
  if (($Classification -eq "snapshot-dependencies" -or $Classification -eq "internal-dependencies") -and
      [System.IO.Path]::GetExtension($Path) -eq ".jar") {
    return Get-JarContentSha256 $Path
  }
  return Get-Sha256 $Path
}

function Resolve-AssetPath([object]$ManifestItem, [string]$AssetsDirectory) {
  $candidates = @(
    (Join-Path $AssetsDirectory $ManifestItem.fileName),
    (Join-Path $AssetsDirectory $ManifestItem.path),
    (Join-Path $AssetsDirectory ("application\" + $ManifestItem.fileName)),
    (Join-Path $AssetsDirectory ("bundles\" + $ManifestItem.fileName))
  )
  foreach ($candidate in $candidates) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  throw "Asset not found for $($ManifestItem.fileName) under $AssetsDirectory."
}

function Receive-Asset([string]$FileName, [string]$Destination, [string]$SourceTag) {
  if ($AssetsDirectory) {
    $asset = Resolve-AssetPath ([pscustomobject]@{ fileName = $FileName; path = $FileName }) $AssetsDirectory
    Copy-Item -LiteralPath $asset -Destination $Destination -Force
    return
  }
  if (-not $Repository -or -not $Tag) {
    throw "Either -AssetsDirectory or both -Repository and -Tag are required."
  }
  $downloadTag = if ($SourceTag) { $SourceTag } else { $Tag }
  gh release download $downloadTag --repo $Repository --pattern $FileName --dir (Split-Path -Parent $Destination) --clobber
}

function Copy-OrHardlink([string]$Source, [string]$Destination) {
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
  try {
    New-Item -ItemType HardLink -Path $Destination -Target $Source -ErrorAction Stop | Out-Null
  } catch {
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
  }
}

function Read-Manifest([string]$Path) {
  Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

# Laufsperre je Dienst: exklusives Handle auf <Dienstordner>\.deploy.lock fuer die ganze Laufzeit. Ein zweiter
# Updater-Lauf fuer denselben Dienst bricht ab, bevor er etwas anlegt, laedt, stoppt oder verschiebt. Die Sperre
# haelt das Betriebssystem am Handle; eine liegen gebliebene Datei sperrt nichts.
function Open-RunLock([string]$Path) {
  try {
    return [System.IO.File]::Open($Path, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
  } catch {
    $inner = $_.Exception
    while ($inner.InnerException) { $inner = $inner.InnerException }
    if ($inner -is [System.IO.IOException]) {
      throw "Laufsperre $Path belegt - ein anderer Updater-Lauf fuer diesen Dienst ist aktiv, Abbruch vor jeder Aenderung."
    }
    throw "Laufsperre $Path nicht anlegbar ($($inner.Message)) - Abbruch vor jeder Aenderung."
  }
}

function Find-Bundle([object]$Manifest, [string]$Classification) {
  @($Manifest.bundles) | Where-Object { $_.classification -eq $Classification } | Select-Object -First 1
}

function Get-BundleAssetName([object]$Bundle) {
  if ($Bundle.assetName) { return $Bundle.assetName }
  if ($Bundle.asset) { return $Bundle.asset }
  return $Bundle.fileName
}

function Get-BundleArtifactSha256([object]$Bundle) {
  if ($Bundle.artifactSha256) { return $Bundle.artifactSha256 }
  return $Bundle.sha256
}

function Get-BundleRelease([object]$Bundle) {
  if ($Bundle.release) { return $Bundle.release }
  return $Tag
}

function Test-Health([string]$Url, [int]$TimeoutSeconds) {
  if (-not $Url) { return }
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    try {
      $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
      if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
        return
      }
    } catch {
      Start-Sleep -Seconds 2
    }
  } while ((Get-Date) -lt $deadline)
  throw "Health check failed: $Url."
}

function Wait-ServiceStatus([string]$Name, [string]$Status, [int]$TimeoutSeconds) {
  $service = Get-Service -Name $Name -ErrorAction Stop
  $service.WaitForStatus($Status, [TimeSpan]::FromSeconds($TimeoutSeconds))
}

# Schutz vor Loeschen ausserhalb des vorgesehenen Ordners (zusaetzlich zur Manifestpruefung): $Path muss nach
# Aufloesung strikt unterhalb von $Parent liegen. Steht unmittelbar vor jedem rekursiven Remove-Item.
function Assert-PathBelow([string]$Path, [string]$Parent) {
  try {
    $fullPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
  } catch {
    throw "Pfad '$Path' liegt nicht strikt unterhalb von '$Parent' (kein gueltiger Pfad) - nichts geloescht."
  }
  if ($fullPath.Length -le $fullParent.Length -or
      -not $fullPath.StartsWith($fullParent, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Pfad '$Path' liegt nicht strikt unterhalb von '$fullParent' - nichts geloescht."
  }
}

# Manifestpruefung (fail-closed): jeder Wert, der spaeter Teil eines Pfads, eines Asset-Namens oder eines
# Vergleichs wird, muss seine Form haben. Laeuft direkt nach dem Lesen des geladenen Manifests, vor jedem Pfad im
# Cache oder Staging, jedem weiteren Download, jedem Loeschen und jedem Stop.
$script:BundleClassifications = @("snapshot-dependencies", "internal-dependencies", "third-party-dependencies")
$script:Sha256Pattern = '^[0-9a-f]{64}\z'
$script:ZipNamePattern = '^[A-Za-z0-9][A-Za-z0-9._-]*\.zip\z'
$script:JarNamePattern = '^[A-Za-z0-9][A-Za-z0-9._-]*\.jar\z'
$script:ReleasePattern = '^[A-Za-z0-9][A-Za-z0-9._-]*\z'
$script:PathSegmentPattern = '^[A-Za-z0-9._+-]+\z'

function Stop-InvalidManifest([string]$Field, [object]$Value) {
  throw "Manifest ungueltig: $Field '$Value' - Abbruch vor jedem Download, jedem Loeschen und jedem Stop."
}

# Wert einer Eigenschaft; $null, wenn das Objekt oder die Eigenschaft fehlt
function Get-ManifestValue([object]$Item, [string]$Name) {
  if ($null -eq $Item -or $Item -isnot [System.Management.Automation.PSCustomObject]) { return $null }
  $property = $Item.PSObject.Properties[$Name]
  if ($property) { return $property.Value }
  return $null
}

function Test-ManifestText([object]$Value, [string]$Pattern) {
  return ($Value -is [string]) -and ($Value -cmatch $Pattern)
}

# Groesse: nicht negative ganze Zahl als JSON-Zahl (kein Text, kein Bruch, kein Wahrheitswert)
function Test-ManifestSize([object]$Value) {
  if ($null -eq $Value -or $Value -is [string] -or $Value -is [bool]) { return $false }
  if (-not ($Value -is [int] -or $Value -is [long] -or $Value -is [decimal] -or $Value -is [double])) { return $false }
  return ([string]$Value) -cmatch '^[0-9]+\z'
}

# Relativer Dateipfad unter application\ oder lib\; Trenner / oder \; kein .- oder ..-Segment, kein leeres Segment,
# kein Laufwerk, kein UNC, keine fuehrenden Trenner; je Segment nur [A-Za-z0-9._+-] und kein Punkt am Ende
# (Windows entfernt ihn still).
function Test-ManifestFilePath([object]$Value) {
  if ($Value -isnot [string] -or [string]::IsNullOrEmpty($Value)) { return $false }
  $segments = @($Value -split '[\\/]')
  if ($segments.Count -lt 2 -or ($segments[0] -cne "application" -and $segments[0] -cne "lib")) { return $false }
  foreach ($segment in $segments) {
    if ($segment -eq "" -or $segment -eq "." -or $segment -eq ".." -or $segment.EndsWith(".") -or
        $segment -cnotmatch $script:PathSegmentPattern) {
      return $false
    }
  }
  return $true
}

function Assert-ManifestValid([object]$Manifest) {
  if ($null -eq $Manifest -or $Manifest -isnot [System.Management.Automation.PSCustomObject]) {
    Stop-InvalidManifest "Wurzel" "kein JSON-Objekt"
  }
  $application = Get-ManifestValue $Manifest "applicationArtifact"
  if ($null -eq $application -or $application -isnot [System.Management.Automation.PSCustomObject]) {
    Stop-InvalidManifest "applicationArtifact" "fehlt"
  }
  $applicationFileName = Get-ManifestValue $application "fileName"
  if (-not (Test-ManifestText $applicationFileName $script:JarNamePattern)) {
    Stop-InvalidManifest "applicationArtifact.fileName" $applicationFileName
  }
  $applicationPath = Get-ManifestValue $application "path"
  if ($applicationPath -isnot [string] -or $applicationPath -cne ("application/" + $applicationFileName)) {
    Stop-InvalidManifest "applicationArtifact.path" $applicationPath
  }
  $applicationSha = Get-ManifestValue $application "sha256"
  if (-not (Test-ManifestText $applicationSha $script:Sha256Pattern)) {
    Stop-InvalidManifest "applicationArtifact.sha256" $applicationSha
  }
  $applicationSize = Get-ManifestValue $application "size"
  if (-not (Test-ManifestSize $applicationSize)) {
    Stop-InvalidManifest "applicationArtifact.size" $applicationSize
  }

  $seen = @()
  $bundleFileCounts = @{}
  foreach ($bundle in @(Get-ManifestValue $Manifest "bundles")) {
    if ($null -eq $bundle -or $bundle -isnot [System.Management.Automation.PSCustomObject]) {
      Stop-InvalidManifest "bundles[]" "kein Objekt"
    }
    $classification = Get-ManifestValue $bundle "classification"
    if ($classification -isnot [string] -or $script:BundleClassifications -cnotcontains $classification) {
      Stop-InvalidManifest "bundles[].classification" $classification
    }
    if ($seen -ccontains $classification) {
      Stop-InvalidManifest "bundles[].classification (doppelt)" $classification
    }
    $seen += $classification
    $contentSha = Get-ManifestValue $bundle "contentSha256"
    if (-not (Test-ManifestText $contentSha $script:Sha256Pattern)) {
      Stop-InvalidManifest "$classification.contentSha256" $contentSha
    }
    $hasArtifactSha = $false
    foreach ($field in @("artifactSha256", "sha256")) {
      $value = Get-ManifestValue $bundle $field
      if ($null -eq $value) { continue }
      if (-not (Test-ManifestText $value $script:Sha256Pattern)) {
        Stop-InvalidManifest "$classification.$field" $value
      }
      $hasArtifactSha = $true
    }
    if (-not $hasArtifactSha) {
      Stop-InvalidManifest "$classification.artifactSha256" "fehlt"
    }
    $hasAssetName = $false
    foreach ($field in @("assetName", "asset", "fileName")) {
      $value = Get-ManifestValue $bundle $field
      if ($null -eq $value) { continue }
      if (-not (Test-ManifestText $value $script:ZipNamePattern)) {
        Stop-InvalidManifest "$classification.$field" $value
      }
      $hasAssetName = $true
    }
    if (-not $hasAssetName) {
      Stop-InvalidManifest "$classification.assetName" "fehlt"
    }
    $release = Get-ManifestValue $bundle "release"
    if ($null -ne $release -and -not (Test-ManifestText $release $script:ReleasePattern)) {
      Stop-InvalidManifest "$classification.release" $release
    }
    $bundleSize = Get-ManifestValue $bundle "size"
    if (-not (Test-ManifestSize $bundleSize)) {
      Stop-InvalidManifest "$classification.size" $bundleSize
    }
    $bundleFileCount = Get-ManifestValue $bundle "fileCount"
    if (-not (Test-ManifestSize $bundleFileCount)) {
      Stop-InvalidManifest "$classification.fileCount" $bundleFileCount
    }
    $bundleFileCounts[$classification] = $bundleFileCount
  }
  foreach ($classification in $script:BundleClassifications) {
    if ($seen -cnotcontains $classification) {
      Stop-InvalidManifest "bundles" "$classification fehlt"
    }
  }

  # Der Gesamtzaehler ist Pflicht (nicht negative ganze JSON-Zahl); den Abgleich mit files[] macht der Schluss
  $summary = Get-ManifestValue $Manifest "summary"
  if ($null -eq $summary -or $summary -isnot [System.Management.Automation.PSCustomObject]) {
    Stop-InvalidManifest "summary" "fehlt oder kein Objekt"
  }
  $summaryFileCount = Get-ManifestValue $summary "fileCount"
  if (-not (Test-ManifestSize $summaryFileCount)) {
    Stop-InvalidManifest "summary.fileCount" $summaryFileCount
  }

  # Jeder Pfad darf nur einmal vorkommen, auch ueber Bundles hinweg: Windows unterscheidet weder Gross- und
  # Kleinschreibung noch / und \, ein spaeterer Eintrag wuerde beim Staging den geprueften Eintrag ueberschreiben.
  # Der Anwendungspfad steht schon vorab in der Menge, damit ihn ein anderer Eintrag auch dann nicht belegen kann,
  # wenn der Anwendungseintrag in files[] fehlt.
  $seenFilePaths = @{}
  $seenFilePaths[$applicationPath.Replace('\', '/').ToLowerInvariant()] = $true
  $applicationEntries = 0
  $filesPerClassification = @{}
  foreach ($classification in $script:BundleClassifications) {
    $filesPerClassification[$classification] = 0
  }
  $totalFiles = 0
  foreach ($file in @(Get-ManifestValue $Manifest "files")) {
    if ($null -eq $file) { continue }
    if ($file -isnot [System.Management.Automation.PSCustomObject]) {
      Stop-InvalidManifest "files[]" "kein Objekt"
    }
    $filePath = Get-ManifestValue $file "path"
    if (-not (Test-ManifestFilePath $filePath)) {
      Stop-InvalidManifest "files[].path" $filePath
    }
    $normalizedFilePath = $filePath.Replace('\', '/').ToLowerInvariant()
    $fileClassification = Get-ManifestValue $file "classification"
    if ($fileClassification -isnot [string] -or
        ($fileClassification -cne "application" -and $script:BundleClassifications -cnotcontains $fileClassification)) {
      Stop-InvalidManifest "files[].classification ($filePath)" $fileClassification
    }
    $totalFiles++
    # Pfadpraefix und Klasse sind aneinander gebunden: die Anwendung liegt genau auf applicationArtifact.path,
    # jede Bibliothek (jede andere Klasse) unter lib/, nie unter application/.
    if ($fileClassification -ceq "application") {
      if ($filePath -cne $applicationPath) {
        Stop-InvalidManifest "files[].path (Anwendung nur als applicationArtifact.path '$applicationPath')" $filePath
      }
      $applicationEntries++
    } else {
      if ($seenFilePaths.ContainsKey($normalizedFilePath)) {
        Stop-InvalidManifest "files[].path (doppelt)" $filePath
      }
      if (-not $normalizedFilePath.StartsWith("lib/", [StringComparison]::Ordinal)) {
        Stop-InvalidManifest "files[].path (Bibliothek der Klasse $fileClassification nur unter lib/)" $filePath
      }
      $seenFilePaths[$normalizedFilePath] = $true
      $filesPerClassification[$fileClassification]++
    }
    $fileSha = Get-ManifestValue $file "sha256"
    if (-not (Test-ManifestText $fileSha $script:Sha256Pattern)) {
      Stop-InvalidManifest "files[].sha256 ($filePath)" $fileSha
    }
    $fileSize = Get-ManifestValue $file "size"
    if (-not (Test-ManifestSize $fileSize)) {
      Stop-InvalidManifest "files[].size ($filePath)" $fileSize
    }
  }
  # Der DistBuilder schreibt die Anwendung immer als genau einen files[]-Eintrag (Pfad wie applicationArtifact.path)
  if ($applicationEntries -ne 1) {
    Stop-InvalidManifest "files[].classification (genau ein Eintrag application erwartet)" "gefunden: $applicationEntries"
  }
  # Zaehler und files[] muessen zusammenpassen: je Bundle-Klasse und insgesamt (samt Anwendung). Ein fehlender oder
  # zusaetzlicher Bibliothekseintrag faellt sonst nicht auf. Vergleich als decimal, damit grosse Zahlen weder
  # ueberlaufen noch still umgewandelt werden.
  foreach ($classification in $script:BundleClassifications) {
    if ([decimal]$bundleFileCounts[$classification] -ne [decimal]$filesPerClassification[$classification]) {
      Stop-InvalidManifest "$classification.fileCount (files[] enthaelt $($filesPerClassification[$classification]))" $bundleFileCounts[$classification]
    }
  }
  if ([decimal]$summaryFileCount -ne [decimal]$totalFiles) {
    Stop-InvalidManifest "summary.fileCount (files[] enthaelt $totalFiles)" $summaryFileCount
  }
}

# Abschlusspruefung des gestagten Standes (fail-closed): laeuft nach dem Staging aller Dateien und vor dem Stop.
# Jede Datei (Anwendung und alle Manifest-Dateien ausser der Anwendung) muss im Staging liegen und Groesse sowie
# Hash des Manifests haben (Get-DeploymentSha256: Snapshot- und interne JARs ueber den Inhaltshash). lib\ darf genau die
# Manifest-Dateien enthalten, keine weitere. Das faengt ab, was die Einzelpruefungen beim Kopieren nicht sehen
# (ueberschriebene oder nachtraeglich geaenderte Dateien, fremde Dateien im Staging).
function Assert-StagedReleaseComplete([object]$Manifest, [string]$Staging) {
  $failureTail = "Abbruch vor dem Stop."
  $checks = New-Object System.Collections.Generic.List[object]
  $application = $Manifest.applicationArtifact
  $checks.Add(@{ Path = [string]$application.path; Size = [int64]$application.size; Sha256 = [string]$application.sha256; Classification = "application" })
  $expectedLibPaths = @{}
  foreach ($file in @($Manifest.files)) {
    if ($file.classification -eq "application") { continue }
    $checks.Add(@{ Path = [string]$file.path; Size = [int64]$file.size; Sha256 = [string]$file.sha256; Classification = [string]$file.classification })
    $expectedLibPaths[([string]$file.path).Replace('\', '/').ToLowerInvariant()] = $true
  }
  foreach ($check in $checks) {
    $staged = Join-Path $Staging $check.Path
    if (-not (Test-Path -LiteralPath $staged -PathType Leaf)) {
      throw "Abschlusspruefung Staging: $($check.Path) fehlt im Staging - $failureTail"
    }
    $actualSize = (Get-Item -LiteralPath $staged).Length
    if ($actualSize -ne $check.Size) {
      throw "Abschlusspruefung Staging: $($check.Path) hat Groesse $actualSize statt $($check.Size) laut Manifest - $failureTail"
    }
    $actualSha256 = if ($check.Classification -eq "application") { Get-Sha256 $staged } else { Get-DeploymentSha256 $staged $check.Classification }
    if ($actualSha256 -ne $check.Sha256) {
      throw "Abschlusspruefung Staging: $($check.Path) weicht vom SHA-256 im Manifest ab - $failureTail"
    }
  }
  $libRoot = [System.IO.Path]::GetFullPath((Join-Path $Staging "lib")).TrimEnd('\') + '\'
  $extraPaths = New-Object System.Collections.Generic.List[string]
  if (Test-Path -LiteralPath $libRoot -PathType Container) {
    foreach ($entry in @(Get-ChildItem -LiteralPath $libRoot -Recurse -Force | Where-Object { -not $_.PSIsContainer })) {
      $relative = "lib/" + $entry.FullName.Substring($libRoot.Length).Replace('\', '/')
      if (-not $expectedLibPaths.ContainsKey($relative.ToLowerInvariant())) {
        $extraPaths.Add($relative)
      }
    }
  }
  if ($extraPaths.Count -gt 0) {
    throw "Abschlusspruefung Staging: lib enthaelt Dateien ausserhalb des Manifests: $($extraPaths -join ', ') - $failureTail"
  }
}

function Compare-ManifestDomain([object]$Current, [object]$Next, [string]$Classification) {
  if (-not $Current) { return $true }
  $old = Find-Bundle $Current $Classification
  $new = Find-Bundle $Next $Classification
  if (-not $old -or -not $new) { return $true }
  return $old.contentSha256 -ne $new.contentSha256
}

function Test-BundleCacheMatchesManifest([object]$Manifest, [object]$Bundle, [string]$CacheRoot) {
  $bundleCache = Join-Path $CacheRoot $Bundle.contentSha256
  if (-not (Test-Path -LiteralPath $bundleCache -PathType Container)) {
    return $false
  }
  foreach ($file in @($Manifest.files | Where-Object { $_.classification -eq $Bundle.classification })) {
    $cachedFile = Join-Path $bundleCache $file.path
    if (-not (Test-Path -LiteralPath $cachedFile -PathType Leaf)) {
      return $false
    }
    if ((Get-Item -LiteralPath $cachedFile).Length -ne [int64]$file.size) {
      return $false
    }
    if ((Get-DeploymentSha256 $cachedFile $file.classification) -ne $file.sha256) {
      return $false
    }
  }
  return $true
}

function Ensure-BundleCache([object]$Manifest, [object]$Bundle, [string]$CacheRoot, [string]$DownloadsRoot) {
  $bundleCache = Join-Path $CacheRoot $Bundle.contentSha256
  if (Test-BundleCacheMatchesManifest $Manifest $Bundle $CacheRoot) {
    return
  }
  if (Test-Path -LiteralPath $bundleCache) {
    Assert-PathBelow $bundleCache $CacheRoot
    Remove-Item -LiteralPath $bundleCache -Recurse -Force
  }
  $assetName = Get-BundleAssetName $Bundle
  $bundleAsset = Join-Path $DownloadsRoot $assetName
  Receive-Asset $assetName $bundleAsset (Get-BundleRelease $Bundle)
  if ((Get-Sha256 $bundleAsset) -ne (Get-BundleArtifactSha256 $Bundle)) {
    throw "Bundle SHA mismatch: $assetName."
  }
  New-Item -ItemType Directory -Force -Path $bundleCache | Out-Null
  Expand-Archive -LiteralPath $bundleAsset -DestinationPath $bundleCache -Force
  if (-not (Test-BundleCacheMatchesManifest $Manifest $Bundle $CacheRoot)) {
    throw "Bundle cache does not match manifest after refresh: $assetName."
  }
}

function Write-DryRunPlan([object]$Current, [object]$Next, [System.Collections.Generic.List[string]]$DownloadsNeeded, [string]$CacheRoot) {
  $applicationAction = if ($DownloadsNeeded.Contains("application")) { "DOWNLOAD" } else { "REUSE" }
  $expectedBytes = [int64]0
  $releasePrefixLength = [Math]::Min(12, $Next.applicationArtifact.sha256.Length)
  $releasePrefix = $Next.applicationArtifact.sha256.Substring(0, $releasePrefixLength)
  if ($applicationAction -eq "DOWNLOAD") {
    $expectedBytes += [int64]$Next.applicationArtifact.size
  }

  Write-Output "Remote manifest:"
  Write-Output ("  application sha256: " + $Next.applicationArtifact.sha256)
  Write-Output ("  application size: " + $Next.applicationArtifact.size)
  foreach ($bundle in @($Next.bundles)) {
    Write-Output ("  " + $bundle.classification + " contentSha256: " + $bundle.contentSha256)
  }

  Write-Output ""
  Write-Output "Installed manifest:"
  if ($Current) {
    Write-Output ("  application sha256: " + $Current.applicationArtifact.sha256)
    foreach ($bundle in @($Current.bundles)) {
      Write-Output ("  " + $bundle.classification + " contentSha256: " + $bundle.contentSha256)
    }
  } else {
    Write-Output "  none"
  }

  Write-Output ""
  Write-Output ("Application: " + $applicationAction)
  foreach ($classification in @("snapshot-dependencies", "internal-dependencies", "third-party-dependencies")) {
    $bundle = Find-Bundle $Next $classification
    $bundleAction = if (Test-BundleCacheMatchesManifest $Next $bundle $CacheRoot) { "REUSE" } else { "DOWNLOAD" }
    if ($bundleAction -eq "DOWNLOAD") {
      $expectedBytes += [int64]$bundle.size
    }
    Write-Output ($classification + ": " + $bundleAction)
  }
  Write-Output ("expected download bytes: " + $expectedBytes)
  Write-Output ("target release: " + $releasePrefix + "-<timestamp>")
}

# Nur der Dienstordner selbst entsteht vor der Laufsperre; sie wird vor jeder weiteren Dateioperation genommen,
# auch bei -DryRun (der liest und schreibt _downloads).
$root = (Resolve-Path -LiteralPath (New-Item -ItemType Directory -Force -Path $DeploymentRoot)).Path
$lockPath = Join-Path $root ".deploy.lock"
$runLock = Open-RunLock $lockPath
try {
  $downloads = Join-Path $root "_downloads"
  $cache = Join-Path $root "cache"
  $stagingRoot = Join-Path $root "_staging"
  $releases = Join-Path $root "releases"
  New-Item -ItemType Directory -Force -Path $downloads | Out-Null

  $manifestPath = Join-Path $downloads "deployment-manifest.json"
  Receive-Asset "deployment-manifest.json" $manifestPath $Tag
  $next = Read-Manifest $manifestPath
  # Vor jedem Pfad aus Manifestwerten, jedem weiteren Download, jedem Loeschen und jedem Stop
  Assert-ManifestValid $next
  New-Item -ItemType Directory -Force -Path $cache, $stagingRoot, $releases | Out-Null
  $currentManifestPath = Join-Path $root "current\deployment-manifest.json"
  $current = $null
  if (Test-Path -LiteralPath $currentManifestPath -PathType Leaf) {
    $current = Read-Manifest $currentManifestPath
  }

  $downloadsNeeded = New-Object System.Collections.Generic.List[string]
  if (-not $current -or $current.applicationArtifact.sha256 -ne $next.applicationArtifact.sha256) {
    $downloadsNeeded.Add("application")
  }
  foreach ($classification in @("snapshot-dependencies", "internal-dependencies", "third-party-dependencies")) {
    if (Compare-ManifestDomain $current $next $classification) {
      $downloadsNeeded.Add($classification)
    }
  }

  if ($DryRun) {
    Write-DryRunPlan $current $next $downloadsNeeded $cache
    return
  }

  $applicationAsset = Join-Path $downloads $next.applicationArtifact.fileName
  if ($downloadsNeeded.Contains("application") -or -not (Test-Path -LiteralPath $applicationAsset)) {
    Receive-Asset $next.applicationArtifact.fileName $applicationAsset $Tag
  }
  if ((Get-Sha256 $applicationAsset) -ne $next.applicationArtifact.sha256) {
    throw "Application artifact SHA mismatch."
  }
  if ((Get-Item -LiteralPath $applicationAsset).Length -ne [int64]$next.applicationArtifact.size) {
    throw "Application artifact size mismatch."
  }

  foreach ($classification in @("snapshot-dependencies", "internal-dependencies", "third-party-dependencies")) {
    $bundle = Find-Bundle $next $classification
    Ensure-BundleCache $next $bundle $cache $downloads
  }

  $releaseId = $next.applicationArtifact.sha256.Substring(0, 12) + "-" + (Get-Date -Format "yyyyMMddHHmmss")
  $staging = Join-Path $stagingRoot $releaseId
  if (Test-Path -LiteralPath $staging) {
    Assert-PathBelow $staging $stagingRoot
    Remove-Item -LiteralPath $staging -Recurse -Force
  }
  New-Item -ItemType Directory -Force -Path (Join-Path $staging "application"), (Join-Path $staging "lib") | Out-Null
  Copy-Item -LiteralPath $applicationAsset -Destination (Join-Path $staging $next.applicationArtifact.path) -Force
  Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $staging "deployment-manifest.json") -Force

  foreach ($file in @($next.files)) {
    if ($file.classification -eq "application") { continue }
    $bundle = Find-Bundle $next $file.classification
    $source = Join-Path (Join-Path $cache $bundle.contentSha256) $file.path
    $destination = Join-Path $staging $file.path
    Copy-OrHardlink $source $destination
    if ((Get-Item -LiteralPath $destination).Length -ne [int64]$file.size) {
      throw "Staged file size mismatch: $($file.path)."
    }
    if ((Get-DeploymentSha256 $destination $file.classification) -ne $file.sha256) {
      throw "Staged file SHA mismatch: $($file.path)."
    }
  }

  # Noch einmal alles gestagte gegen das Manifest, vor dem Stop und vor jedem Tausch
  Assert-StagedReleaseComplete $next $staging

  $previous = Join-Path $root ("releases\previous-" + (Get-Date -Format "yyyyMMddHHmmss"))
  $currentPath = Join-Path $root "current"
  $failed = Join-Path $root ("releases\failed-" + $releaseId)

  if ($ServiceName) {
    Stop-Service -Name $ServiceName -Force
    Wait-ServiceStatus $ServiceName "Stopped" 60
  }

  try {
    if (Test-Path -LiteralPath $currentPath -PathType Container) {
      Move-Item -LiteralPath $currentPath -Destination $previous
    }
    Move-Item -LiteralPath $staging -Destination $currentPath
    if ($ServiceName) {
      Start-Service -Name $ServiceName
    }
    Test-Health $HealthUrl $HealthTimeoutSeconds
    Write-Host "DEPLOY SUCCESS $releaseId"
  } catch {
    if ($ServiceName) {
      Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $currentPath -PathType Container) {
      Move-Item -LiteralPath $currentPath -Destination $failed -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $previous -PathType Container) {
      Move-Item -LiteralPath $previous -Destination $currentPath
      if ($ServiceName) { Start-Service -Name $ServiceName }
    }
    throw
  }
} finally {
  # Laufsperre freigeben; die Datei darf liegen bleiben (die Sperre ist das offene Handle, nicht die Datei)
  $runLock.Dispose()
}
