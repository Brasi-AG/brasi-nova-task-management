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

$root = (Resolve-Path -LiteralPath (New-Item -ItemType Directory -Force -Path $DeploymentRoot)).Path
$downloads = Join-Path $root "_downloads"
$cache = Join-Path $root "cache"
$stagingRoot = Join-Path $root "_staging"
$releases = Join-Path $root "releases"
New-Item -ItemType Directory -Force -Path $downloads, $cache, $stagingRoot, $releases | Out-Null

$manifestPath = Join-Path $downloads "deployment-manifest.json"
Receive-Asset "deployment-manifest.json" $manifestPath $Tag
$next = Read-Manifest $manifestPath
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
if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
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
