param(
  [Parameter(Mandatory = $true)]
  [string]$DistDirectory,

  [string]$ManifestPath,
  [Parameter(Mandatory = $true)]
  [string]$Repository,
  [string]$ContentStoreTag = "deployment-content-store",
  [string]$Target = "HEAD",
  [string]$Title = "Deployment Content Store"
)

$ErrorActionPreference = "Stop"

function Get-Sha256([string]$Path) {
  (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Invoke-Gh([string[]]$Arguments, [switch]$AllowFailure) {
  $previousErrorActionPreference = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $output = & gh @Arguments 2>&1
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousErrorActionPreference
  }
  if ($exitCode -ne 0 -and -not $AllowFailure) {
    throw "gh $($Arguments -join ' ') failed with exit code $exitCode. $($output | Out-String)"
  }
  [pscustomobject]@{
    ExitCode = $exitCode
    Output = ($output | Out-String)
  }
}

function Get-ReleaseAssetNames([string]$Tag) {
  $result = Invoke-Gh @("release", "view", $Tag, "--json", "assets", "--repo", $Repository) -AllowFailure
  if ($result.ExitCode -ne 0) {
    return $null
  }
  try {
    $json = $result.Output | ConvertFrom-Json
    return [string[]]@($json.assets | ForEach-Object { $_.name })
  } catch {
    return [regex]::Matches($result.Output, '"name"\s*:\s*"([^"]+)"') |
      ForEach-Object { $_.Groups[1].Value }
  }
}

function Ensure-ContentStoreRelease() {
  $assets = Get-ReleaseAssetNames $ContentStoreTag
  if ($null -ne $assets) {
    return
  }

  $created = Invoke-Gh @(
    "release", "create", $ContentStoreTag,
    "--repo", $Repository,
    "--target", $Target,
    "--title", $Title,
    "--notes", "Immutable deployment dependency bundles addressed by contentSha256.",
    "--prerelease"
  ) -AllowFailure
  if ($created.ExitCode -eq 0) {
    return
  }

  $assets = Get-ReleaseAssetNames $ContentStoreTag
  if ($null -eq $assets) {
    throw "Content store release $ContentStoreTag could not be created or resolved. $($created.Output)"
  }
}

function Receive-ContentStoreAsset([string]$AssetName, [string]$Destination) {
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
  Invoke-Gh @(
    "release", "download", $ContentStoreTag,
    "--repo", $Repository,
    "--pattern", $AssetName,
    "--dir", (Split-Path -Parent $Destination),
    "--clobber"
  ) | Out-Null
}

function Test-RemoteAsset([object]$Bundle, [string]$TempRoot) {
  $assetName = Get-BundleAssetName $Bundle
  $downloaded = Join-Path $TempRoot $assetName
  if (Test-Path -LiteralPath $downloaded) {
    Remove-Item -LiteralPath $downloaded -Force
  }
  Receive-ContentStoreAsset $assetName $downloaded
  [pscustomobject]@{
    Sha256 = Get-Sha256 $downloaded
    Size = (Get-Item -LiteralPath $downloaded).Length
  }
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

function Ensure-ContentStoreAsset([object]$Bundle, [string]$TempRoot) {
  $assetName = Get-BundleAssetName $Bundle
  $artifactSha = Get-BundleArtifactSha256 $Bundle
  $bundlePath = Join-Path $DistDirectory $assetName
  if (-not (Test-Path -LiteralPath $bundlePath -PathType Leaf)) {
    $bundlePath = Join-Path $DistDirectory ("bundles\" + $assetName)
  }
  if (-not (Test-Path -LiteralPath $bundlePath -PathType Leaf)) {
    throw "Local bundle asset missing: $assetName."
  }
  if ((Get-Sha256 $bundlePath) -ne $artifactSha) {
    throw "Local bundle artifact SHA mismatch before upload: $assetName."
  }

  $assets = Get-ReleaseAssetNames $ContentStoreTag
  if ($assets -contains $assetName) {
    $remote = Test-RemoteAsset $Bundle $TempRoot
    Write-Output ("{0}: REUSE {1} bytes asset={2}" -f $Bundle.classification, $Bundle.size, $assetName)
    return [pscustomobject]@{
      Classification = $Bundle.classification
      Action = "REUSE"
      Bytes = 0
      AssetName = $assetName
      ArtifactSha256 = $remote.Sha256
      Size = $remote.Size
    }
  }

  $upload = Invoke-Gh @("release", "upload", $ContentStoreTag, $bundlePath, "--repo", $Repository) -AllowFailure
  if ($upload.ExitCode -eq 0) {
    $remote = Test-RemoteAsset $Bundle $TempRoot
    Write-Output ("{0}: UPLOAD {1} bytes asset={2}" -f $Bundle.classification, $Bundle.size, $assetName)
    return [pscustomobject]@{
      Classification = $Bundle.classification
      Action = "UPLOAD"
      Bytes = [int64]$Bundle.size
      AssetName = $assetName
      ArtifactSha256 = $remote.Sha256
      Size = $remote.Size
    }
  }

  if ($upload.Output -notmatch "already exists|already_exists|Validation Failed|name_already_exists") {
    throw "Upload failed for $assetName. $($upload.Output)"
  }

  Write-Output ("{0}: upload conflict, rechecking immutable asset" -f $Bundle.classification)
  $assets = Get-ReleaseAssetNames $ContentStoreTag
  if ($assets -notcontains $assetName) {
    throw "Upload conflict for $assetName but asset was not visible afterwards."
  }
  $remote = Test-RemoteAsset $Bundle $TempRoot
  Write-Output ("{0}: REUSE {1} bytes asset={2}" -f $Bundle.classification, $Bundle.size, $assetName)
  return [pscustomobject]@{
    Classification = $Bundle.classification
    Action = "REUSE"
    Bytes = 0
    AssetName = $assetName
    ArtifactSha256 = $remote.Sha256
    Size = $remote.Size
  }
}

function Update-ManifestBundleReferences([object]$Manifest, [object[]]$Results, [string]$Path) {
  foreach ($result in @($Results)) {
    $bundle = @($Manifest.bundles) |
      Where-Object { $_.classification -eq $result.Classification } |
      Select-Object -First 1
    if (-not $bundle) {
      throw "Manifest bundle missing for $($result.Classification)."
    }
    $bundle.assetName = $result.AssetName
    $bundle.release = $ContentStoreTag
    $bundle.artifactSha256 = $result.ArtifactSha256
    $bundle.sha256 = $result.ArtifactSha256
    $bundle.size = [int64]$result.Size
  }
  $Manifest | ConvertTo-Json -Depth 100 |
    Set-Content -LiteralPath $Path -Encoding UTF8
}

$root = (Resolve-Path -LiteralPath (New-Item -ItemType Directory -Force -Path $DistDirectory)).Path
if (-not $ManifestPath) {
  $ManifestPath = Join-Path $root "deployment-manifest.json"
}
$manifest = Get-Content -Raw -LiteralPath $ManifestPath | ConvertFrom-Json
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("deployment-content-store-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

try {
  Ensure-ContentStoreRelease
  $results = @()
  foreach ($bundle in @($manifest.bundles)) {
    $output = Ensure-ContentStoreAsset $bundle $tempRoot
    foreach ($item in @($output)) {
      if ($item -is [string]) {
        Write-Output $item
      } else {
        $results += $item
      }
    }
  }
  Update-ManifestBundleReferences $manifest $results $ManifestPath
  $uploadedBytes = ($results | Measure-Object -Property Bytes -Sum).Sum
  if ($null -eq $uploadedBytes) { $uploadedBytes = 0 }
  Write-Output ("dependency content-store uploaded bytes: " + [int64]$uploadedBytes)
} finally {
  if (Test-Path -LiteralPath $tempRoot) {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
  }
}
