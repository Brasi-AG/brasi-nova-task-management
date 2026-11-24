# Pester-Tests (ab 5.6, Windows PowerShell 5.1) fuer den inkrementellen Thin-JAR-Updater neben dieser Datei:
# Manifestpruefung, Loeschschutz, Laufsperre und Abschlusspruefung des Stagings. Muster:
# keel-platform-infrastructure, scripts/update-incremental-kpl-deployment.Tests.ps1. Alles laeuft unter TestDrive;
# gh, Dienste und HTTP sind Attrappen.
#
# testdata/deployment-manifest.json ist ein echtes Manifest des DeploymentDistBuilder, nicht nachgebaut: Asset des
# Releases snapshot-task-management-rt-45438992289 (developer d79501b), SHA-256 des Assets
# 603b1c18985773578b8e6abdf7cfc0a3be03579d2f118a32754b76432fecf1ad.
BeforeAll {
  $ErrorActionPreference = "Stop"

  function Get-TestSha256([string]$Path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
  }

  # Gueltiger Hash (64 Hex-Zeichen, klein) aus einer lesbaren Bezeichnung; der Updater prueft die Form jedes Manifest-Hashs
  function Get-LabelSha256([string]$Label) {
    $digest = [System.Security.Cryptography.SHA256]::Create()
    try {
      return ([BitConverter]::ToString($digest.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Label))) -replace "-", "").ToLowerInvariant()
    } finally {
      $digest.Dispose()
    }
  }

  function New-FakeGhDownloadCommand([string]$Root) {
    $bin = Join-Path $Root "bin"
    $store = Join-Path $Root "store"
    New-Item -ItemType Directory -Force -Path $bin, $store | Out-Null
    $gh = Join-Path $bin "gh.cmd"
    @"
@echo off
setlocal enabledelayedexpansion
echo %*>> "%GH_FAKE_LOG%"
if "%1 %2"=="release download" (
  set tag=%3
  shift
  shift
  shift
  :loop
  if "%1"=="--pattern" set pattern=%2
  if "%1"=="--dir" set outdir=%2
  shift
  if not "%1"=="" goto loop
  copy /Y "%GH_FAKE_STORE%\!tag!\!pattern!" "!outdir!\!pattern!" >nul
  exit /b %ERRORLEVEL%
)
exit /b 99
"@ | Set-Content -LiteralPath $gh -Encoding ASCII
    @{
      Bin = $bin
      Store = $store
      Log = Join-Path $Root "gh.log"
    }
  }

  $script:UpdaterPath = $PSCommandPath.Replace(".Tests.ps1", ".ps1")
  $script:RealManifestPath = Join-Path $PSScriptRoot "testdata\deployment-manifest.json"

  function Invoke-Updater([hashtable]$Arguments) {
    $result = @{ Output = ""; Failure = $null }
    # Ausgabe zeilenweise sammeln, damit sie auch bei einem Abbruch des Updaters erhalten bleibt
    $lines = New-Object System.Collections.Generic.List[object]
    try {
      & $script:UpdaterPath @Arguments 6>&1 | ForEach-Object { $lines.Add($_) }
    } catch {
      $result.Failure = $_.Exception.Message
    }
    $result.Output = [string]($lines.ToArray() | Out-String)
    return $result
  }

  # Liest Funktionen per AST aus dem Updater, damit Einzeltests sie ohne Updater-Lauf einbinden koennen
  function Get-UpdaterFunctions([string[]]$Names) {
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($script:UpdaterPath, [ref]$tokens, [ref]$parseErrors)
    return @($ast.FindAll({
          param($node)
          $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Names -contains $node.Name
        }, $true))
  }

  $global:UpdaterCalls = New-Object System.Collections.Generic.List[string]
}

AfterAll {
  Remove-Variable UpdaterCalls -Scope Global -ErrorAction SilentlyContinue
}

Describe "incremental updater Windows PowerShell compatibility" {
  # Ohne BOM liest Windows PowerShell 5.1 die Datei als ANSI; Nicht-ASCII-Zeichen wuerden still veraendert
  It "parses without errors and contains only ASCII" {
    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($script:UpdaterPath, [ref]$tokens, [ref]$parseErrors)
    @($parseErrors).Count | Should -Be 0
    @([System.IO.File]::ReadAllBytes($script:UpdaterPath) | Where-Object { $_ -gt 127 }).Count | Should -Be 0
  }
}

Describe "incremental updater with a real DeploymentDistBuilder manifest" {
  BeforeAll {
    function New-RealManifestFixture([string]$Name, [scriptblock]$Mutate) {
      $base = Join-Path $TestDrive $Name
      $assets = Join-Path $base "assets"
      New-Item -ItemType Directory -Force -Path $assets | Out-Null
      $manifest = Get-Content -Raw -LiteralPath $script:RealManifestPath | ConvertFrom-Json
      $target = Join-Path $assets "deployment-manifest.json"
      if ($Mutate) {
        & $Mutate $manifest
        $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $target -Encoding UTF8
      } else {
        Copy-Item -LiteralPath $script:RealManifestPath -Destination $target
      }
      @{ Root = (Join-Path $base "svc"); Assets = $assets; Manifest = $manifest }
    }
  }

  It "accepts the real manifest and prints the plan" {
    $fixture = New-RealManifestFixture "real-valid"

    $result = Invoke-Updater @{ DeploymentRoot = $fixture.Root; AssetsDirectory = $fixture.Assets; DryRun = $true }

    $result.Failure | Should -BeNullOrEmpty
    $result.Output | Should -Match "Remote manifest:"
    $result.Output | Should -Match ("application sha256: " + $fixture.Manifest.applicationArtifact.sha256)
    $result.Output | Should -Match "Installed manifest:"
    $result.Output | Should -Match "third-party-dependencies: DOWNLOAD"
  }

  It "rejects the real manifest once <case>" -ForEach @(
    @{ case = "contentSha256 leaves the cache"; mutate = { param($m) $m.bundles[0].contentSha256 = '..\..\_deploy' } },
    @{ case = "the last file path leaves the staging"; mutate = { param($m) $m.files[$m.files.Count - 1].path = 'lib/../../winsw/x.xml' } },
    @{ case = "a library entry is removed while the counters stay"; mutate = { param($m) $first = @($m.files | Where-Object { $_.classification -ne 'application' })[0]; $m.files = @($m.files | Where-Object { $_.path -ne $first.path }) } },
    @{ case = "a library entry is added while the counters stay"; mutate = { param($m) $first = @($m.files | Where-Object { $_.classification -ne 'application' })[0]; $m.files = @($m.files) + [pscustomobject]@{ path = 'lib/zz-extra.jar'; fileName = 'zz-extra.jar'; classification = $first.classification; sha256 = $first.sha256; size = $first.size } } }
  ) {
    $fixture = New-RealManifestFixture ("real-" + (Get-LabelSha256 $case).Substring(0, 12)) $mutate

    $result = Invoke-Updater @{ DeploymentRoot = $fixture.Root; AssetsDirectory = $fixture.Assets; DryRun = $true }

    $result.Failure | Should -Match "Manifest ungueltig"
    $result.Output | Should -Not -Match "Remote manifest:"
  }
}

Describe "incremental updater manifest validation and run lock" {
  BeforeAll {
    $script:OriginalPath = $env:PATH

    # Dienstordner <Name>\svc mit altem Stand, Opferordner <Name>\_deploy daneben (Ziel von ..\..\_deploy aus dem Cache),
    # Manifest mit drei Bundles und einer lib-Datei (Drittanbieter), gh-Attrappe mit Tag "tag-1".
    # -WithCache legt den passenden Cache an (gueltiger Lauf ohne Bundle-Download).
    function New-ValidationFixture([string]$Name, [scriptblock]$Mutate, [switch]$WithCache) {
      $base = Join-Path $TestDrive $Name
      $root = Join-Path $base "svc"
      $assets = Join-Path $base "assets"
      $victim = Join-Path $base "_deploy"
      New-Item -ItemType Directory -Force -Path $assets, (Join-Path $root "current"), $victim | Out-Null
      Set-Content -LiteralPath (Join-Path $root "current\sentinel.txt") -Value "previous release" -Encoding ASCII
      Set-Content -LiteralPath (Join-Path $victim "keep.txt") -Value "keep" -Encoding ASCII
      $app = Join-Path $assets "app.jar"
      Set-Content -LiteralPath $app -Value "new application" -Encoding ASCII
      $dependency = Join-Path $base "dep.jar"
      Set-Content -LiteralPath $dependency -Value "third-party dependency" -Encoding ASCII
      $manifest = @{
        schemaVersion = 1
        applicationArtifact = @{ path = "application/app.jar"; fileName = "app.jar"; sha256 = Get-TestSha256 $app; size = (Get-Item $app).Length }
        bundles = @(
          @{ classification = "snapshot-dependencies"; fileName = "snapshot.zip"; assetName = "snapshot.zip"; release = "deployment-content-store"
             contentSha256 = (Get-LabelSha256 "v-snapshot"); sha256 = (Get-LabelSha256 "v-snapshot-zip"); artifactSha256 = (Get-LabelSha256 "v-snapshot-zip"); size = 0; fileCount = 0 },
          @{ classification = "internal-dependencies"; fileName = "internal.zip"; assetName = "internal.zip"; release = "deployment-content-store"
             contentSha256 = (Get-LabelSha256 "v-internal"); sha256 = (Get-LabelSha256 "v-internal-zip"); artifactSha256 = (Get-LabelSha256 "v-internal-zip"); size = 0; fileCount = 0 },
          @{ classification = "third-party-dependencies"; fileName = "third.zip"; assetName = "third.zip"; release = "deployment-content-store"
             contentSha256 = (Get-LabelSha256 "v-third"); sha256 = (Get-LabelSha256 "v-third-zip"); artifactSha256 = (Get-LabelSha256 "v-third-zip"); size = 0; fileCount = 1 }
        )
        files = @(
          @{ path = "application/app.jar"; fileName = "app.jar"; classification = "application"; sha256 = Get-TestSha256 $app; size = (Get-Item $app).Length },
          @{ path = "lib/dep.jar"; fileName = "dep.jar"; classification = "third-party-dependencies"; sha256 = Get-TestSha256 $dependency; size = (Get-Item $dependency).Length }
        )
        summary = @{ fileCount = 2 }
      }
      $object = $manifest | ConvertTo-Json -Depth 10 | ConvertFrom-Json
      if ($Mutate) { & $Mutate $object }
      $object | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $assets "deployment-manifest.json") -Encoding UTF8
      if ($WithCache) {
        foreach ($label in @("v-snapshot", "v-internal")) {
          New-Item -ItemType Directory -Force -Path (Join-Path $root ("cache\" + (Get-LabelSha256 $label))) | Out-Null
        }
        $cachedLib = Join-Path $root ("cache\" + (Get-LabelSha256 "v-third") + "\lib")
        New-Item -ItemType Directory -Force -Path $cachedLib | Out-Null
        Copy-Item -LiteralPath $dependency -Destination (Join-Path $cachedLib "dep.jar")
      }
      $gh = New-FakeGhDownloadCommand (Join-Path $base "gh")
      $tagDir = Join-Path $gh.Store "tag-1"
      New-Item -ItemType Directory -Force -Path $tagDir | Out-Null
      Copy-Item -Path (Join-Path $assets "*") -Destination $tagDir
      # Attrappe vor jedem echten gh; AfterEach stellt den Suchpfad wieder her
      $env:PATH = $gh.Bin + ";" + $script:OriginalPath
      $env:GH_FAKE_STORE = $gh.Store
      $env:GH_FAKE_LOG = $gh.Log
      @{ Root = $root; Victim = $victim; GhLog = $gh.Log; Store = $tagDir; Lock = (Join-Path $root ".deploy.lock") }
    }

    function Get-LibBundle([object]$Manifest) {
      @($Manifest.bundles) | Where-Object { $_.classification -eq "third-party-dependencies" } | Select-Object -First 1
    }

    function Get-LibFile([object]$Manifest) {
      @($Manifest.files) | Where-Object { $_.classification -eq "third-party-dependencies" } | Select-Object -First 1
    }

    function Get-ApplicationFile([object]$Manifest) {
      @($Manifest.files) | Where-Object { $_.classification -eq "application" } | Select-Object -First 1
    }

    # Entfernt den Anwendungseintrag aus files[]; applicationArtifact bleibt stehen
    function Remove-ApplicationFile([object]$Manifest) {
      $Manifest.files = @(@($Manifest.files) | Where-Object { $_.classification -ne "application" })
    }

    # Haengt einen weiteren files[]-Eintrag mit Pfad und Klasse an; Groesse und Hash stammen von der lib-Datei
    function Add-ManifestFile([object]$Manifest, [string]$Path, [string]$Classification) {
      $source = Get-LibFile $Manifest
      $copy = [pscustomobject]@{ path = $Path; fileName = "dep.jar"; classification = $Classification; sha256 = $source.sha256; size = $source.size }
      $Manifest.files = @($Manifest.files) + $copy
    }

    # Nimmt den files[]-Eintrag mit diesem Pfad heraus; die Zaehler im Manifest bleiben stehen
    function Remove-ManifestFile([object]$Manifest, [string]$Path) {
      $Manifest.files = @(@($Manifest.files) | Where-Object { $_.path -ne $Path })
    }

    function Invoke-ValidationUpdater([hashtable]$Fixture, [switch]$DryRun) {
      $arguments = @{ DeploymentRoot = $Fixture.Root; Repository = "owner/repo"; Tag = "tag-1"; ServiceName = "svc"; HealthUrl = "http://localhost:60001/health" }
      if ($DryRun) { $arguments["DryRun"] = $true }
      # Remove-Item nur um den Aufruf herum mitschreiben; die echte Loeschung laeuft weiter
      function global:Remove-Item {
        param([string]$LiteralPath, [switch]$Recurse, [switch]$Force, [object]$ErrorAction)
        $global:UpdaterCalls.Add("Remove $LiteralPath")
        Microsoft.PowerShell.Management\Remove-Item @PSBoundParameters
      }
      try {
        return Invoke-Updater $arguments
      } finally {
        Microsoft.PowerShell.Management\Remove-Item Function:\Remove-Item -Force -ErrorAction SilentlyContinue
      }
    }

    function Get-GhLogLines([string]$Log) {
      if (-not (Test-Path -LiteralPath $Log -PathType Leaf)) { return @() }
      return @(Get-Content -LiteralPath $Log | Where-Object { $_ })
    }
  }

  BeforeEach {
    $global:UpdaterCalls.Clear()
    $global:UpdaterServiceStatus = "Running"
    function global:Get-Service {
      param([string]$Name, [object]$ErrorAction)
      $service = [pscustomobject]@{ Name = $Name; Status = $global:UpdaterServiceStatus; DependentServices = @() }
      $service | Add-Member -MemberType ScriptMethod -Name WaitForStatus -Value { param($status, $timeout) }
      return $service
    }
    function global:Stop-Service {
      param([string]$Name, [switch]$Force, [object]$ErrorAction)
      $global:UpdaterCalls.Add("Stop $Name")
      $global:UpdaterServiceStatus = "Stopped"
    }
    function global:Start-Service {
      param([string]$Name, [object]$ErrorAction)
      $global:UpdaterCalls.Add("Start $Name")
      $global:UpdaterServiceStatus = "Running"
    }
    function global:Invoke-WebRequest {
      param([string]$Uri, [switch]$UseBasicParsing, [int]$TimeoutSec)
      [pscustomobject]@{ StatusCode = 200; Content = '{"status":"UP"}' }
    }
  }

  AfterEach {
    $env:PATH = $script:OriginalPath
    Remove-Item Function:\Get-Service, Function:\Stop-Service, Function:\Start-Service, Function:\Invoke-WebRequest -Force -ErrorAction SilentlyContinue
    Remove-Item Env:\GH_FAKE_STORE, Env:\GH_FAKE_LOG -ErrorAction SilentlyContinue
    Remove-Variable UpdaterServiceStatus -Scope Global -ErrorAction SilentlyContinue
    $global:UpdaterCalls.Clear()
  }

  It "rejects a manifest with <case> before any cache, staging, download, delete or stop" -ForEach @(
    @{ case = "contentSha256 mit Pfadausbruch"; mutate = { param($m) (Get-LibBundle $m).contentSha256 = '..\..\_deploy' } },
    @{ case = "contentSha256 in Grossbuchstaben"; mutate = { param($m) $b = Get-LibBundle $m; $b.contentSha256 = $b.contentSha256.ToUpperInvariant() } },
    @{ case = "contentSha256 zu kurz"; mutate = { param($m) (Get-LibBundle $m).contentSha256 = 'abc123' } },
    @{ case = "artifactSha256 kein Hash"; mutate = { param($m) (Get-LibBundle $m).artifactSha256 = 'unused' } },
    @{ case = "Bundle-sha256 kein Hash"; mutate = { param($m) (Get-LibBundle $m).sha256 = 'unused' } },
    @{ case = "applicationArtifact.sha256 kein Hash"; mutate = { param($m) $m.applicationArtifact.sha256 = 'new-app' } },
    @{ case = "Datei-sha256 kein Hash"; mutate = { param($m) (Get-LibFile $m).sha256 = '..\x' } },
    @{ case = "negativer Bundle-Groesse"; mutate = { param($m) (Get-LibBundle $m).size = -1 } },
    @{ case = "gebrochener Dateigroesse"; mutate = { param($m) (Get-LibFile $m).size = 1.5 } },
    @{ case = "Dateigroesse als Text"; mutate = { param($m) (Get-LibFile $m).size = "12" } },
    @{ case = "fehlender Anwendungsgroesse"; mutate = { param($m) $m.applicationArtifact.PSObject.Properties.Remove("size") } },
    @{ case = "unbekannter Bundle-Klasse"; mutate = { param($m) (Get-LibBundle $m).classification = 'evil-dependencies' } },
    @{ case = "fehlendem Bundle"; mutate = { param($m) $m.bundles = @($m.bundles | Where-Object { $_.classification -ne 'third-party-dependencies' }) } },
    @{ case = "assetName mit Pfad"; mutate = { param($m) (Get-LibBundle $m).assetName = '..\evil.zip' } },
    @{ case = "Bundle-fileName ohne zip"; mutate = { param($m) (Get-LibBundle $m).fileName = 'internal.exe' } },
    @{ case = "release mit Pfad"; mutate = { param($m) (Get-LibBundle $m).release = '../store' } },
    @{ case = "applicationArtifact.fileName mit Pfad"; mutate = { param($m) $m.applicationArtifact.fileName = '..\app.jar'; $m.applicationArtifact.path = 'application/..\app.jar' } },
    @{ case = "applicationArtifact.path abweichend"; mutate = { param($m) $m.applicationArtifact.path = 'lib/app.jar' } },
    @{ case = "Dateipfad mit .."; mutate = { param($m) (Get-LibFile $m).path = 'lib/../../_deploy/keep.txt' } },
    @{ case = "Dateipfad mit ."; mutate = { param($m) (Get-LibFile $m).path = 'lib/./dep.jar' } },
    @{ case = "Dateipfad mit Laufwerk"; mutate = { param($m) (Get-LibFile $m).path = 'C:\Windows\evil.jar' } },
    @{ case = "Dateipfad als UNC"; mutate = { param($m) (Get-LibFile $m).path = '\\server\share\lib\evil.jar' } },
    @{ case = "Dateipfad gerootet"; mutate = { param($m) (Get-LibFile $m).path = '\lib\evil.jar' } },
    @{ case = "Dateipfad mit leerem Segment"; mutate = { param($m) (Get-LibFile $m).path = 'lib//dep.jar' } },
    @{ case = "Dateipfad ausserhalb von application und lib"; mutate = { param($m) (Get-LibFile $m).path = 'conf/dep.jar' } },
    @{ case = "Dateiname mit Datenstrom"; mutate = { param($m) (Get-LibFile $m).path = 'lib/dep.jar:evil' } },
    @{ case = "unbekannter Dateiklasse"; mutate = { param($m) (Get-LibFile $m).classification = 'evil-dependencies' } },
    @{ case = "doppeltem Dateipfad in zwei Bundles"; mutate = { param($m) Add-ManifestFile $m 'lib/dep.jar' 'internal-dependencies' } },
    @{ case = "doppeltem Dateipfad im selben Bundle"; mutate = { param($m) Add-ManifestFile $m 'lib/dep.jar' 'third-party-dependencies' } },
    @{ case = "Dateipfad doppelt nur in der Schreibweise"; mutate = { param($m) Add-ManifestFile $m 'lib/DEP.jar' 'internal-dependencies' } },
    @{ case = "Dateipfad doppelt nur im Trenner"; mutate = { param($m) Add-ManifestFile $m 'lib\dep.jar' 'internal-dependencies' } },
    @{ case = "Dateipfad doppelt in Trenner und Schreibweise"; mutate = { param($m) (Get-LibFile $m).path = 'lib\Dep.jar'; Add-ManifestFile $m 'lib/dep.JAR' 'snapshot-dependencies' } },
    @{ case = "doppeltem Anwendungspfad"; mutate = { param($m) Add-ManifestFile $m 'application/app.jar' 'third-party-dependencies' } },
    @{ case = "lib-klassifiziertem Eintrag unter application"; mutate = { param($m) (Get-LibFile $m).path = 'application/dep.jar' } },
    @{ case = "lib-klassifiziertem Eintrag unter application mit Backslash"; mutate = { param($m) (Get-LibFile $m).path = 'application\dep.jar' } },
    @{ case = "Bibliothek der Klasse snapshot unter application"; mutate = { param($m) Add-ManifestFile $m 'application/snapshot.jar' 'snapshot-dependencies' } },
    @{ case = "Anwendungseintrag mit abweichendem Pfad"; mutate = { param($m) (Get-ApplicationFile $m).path = 'application/other.jar' } },
    @{ case = "Anwendungseintrag unter lib"; mutate = { param($m) (Get-ApplicationFile $m).path = 'lib/app.jar' } },
    @{ case = "Anwendungseintrag nur in der Schreibweise abweichend"; mutate = { param($m) (Get-ApplicationFile $m).path = 'application/APP.jar' } },
    @{ case = "Anwendungseintrag nur im Trenner abweichend"; mutate = { param($m) (Get-ApplicationFile $m).path = 'application\app.jar' } },
    @{ case = "fehlendem Anwendungseintrag in files"; mutate = { param($m) Remove-ApplicationFile $m } },
    @{ case = "fehlendem Anwendungseintrag und lib-Eintrag mit Anwendungspfad"; mutate = { param($m) Remove-ApplicationFile $m; Add-ManifestFile $m 'application/app.jar' 'internal-dependencies' } },
    @{ case = "zwei Anwendungseintraegen"; mutate = { param($m) Add-ManifestFile $m 'application/app.jar' 'application' } },
    @{ case = "zwei Anwendungseintraegen mit zweitem Pfad"; mutate = { param($m) Add-ManifestFile $m 'application/other.jar' 'application' } },
    @{ case = "contentSha256 mit Zeilenende"; mutate = { param($m) $b = Get-LibBundle $m; $b.contentSha256 = $b.contentSha256 + "`n" } },
    @{ case = "artifactSha256 mit Zeilenende"; mutate = { param($m) $b = Get-LibBundle $m; $b.artifactSha256 = $b.artifactSha256 + "`n" } },
    @{ case = "Bundle-sha256 mit Zeilenende"; mutate = { param($m) $b = Get-LibBundle $m; $b.sha256 = $b.sha256 + "`n" } },
    @{ case = "applicationArtifact.sha256 mit Zeilenende"; mutate = { param($m) $m.applicationArtifact.sha256 = $m.applicationArtifact.sha256 + "`n" } },
    @{ case = "Datei-sha256 mit Zeilenende"; mutate = { param($m) $f = Get-LibFile $m; $f.sha256 = $f.sha256 + "`n" } },
    @{ case = "assetName mit Zeilenende"; mutate = { param($m) $b = Get-LibBundle $m; $b.assetName = $b.assetName + "`n" } },
    @{ case = "Bundle-fileName mit Zeilenende"; mutate = { param($m) $b = Get-LibBundle $m; $b.fileName = $b.fileName + "`n" } },
    @{ case = "release mit Zeilenende"; mutate = { param($m) $b = Get-LibBundle $m; $b.release = $b.release + "`n" } },
    @{ case = "applicationArtifact.fileName mit Zeilenende"; mutate = { param($m) $m.applicationArtifact.fileName = "app.jar`n"; $m.applicationArtifact.path = "application/app.jar`n" } },
    @{ case = "Dateipfad mit Zeilenende"; mutate = { param($m) $f = Get-LibFile $m; $f.path = $f.path + "`n" } },
    @{ case = "Anwendungsdateipfad mit Zeilenende"; mutate = { param($m) $f = Get-ApplicationFile $m; $f.path = $f.path + "`n" } },
    @{ case = "fehlendem summary"; mutate = { param($m) $m.PSObject.Properties.Remove("summary") } },
    @{ case = "summary ohne Objekt"; mutate = { param($m) $m.summary = 2 } },
    @{ case = "fehlendem summary.fileCount"; mutate = { param($m) $m.summary.PSObject.Properties.Remove("fileCount") } },
    @{ case = "summary.fileCount als Text"; mutate = { param($m) $m.summary.fileCount = "2" } },
    @{ case = "negativem summary.fileCount"; mutate = { param($m) $m.summary.fileCount = -1 } },
    @{ case = "gebrochenem summary.fileCount"; mutate = { param($m) $m.summary.fileCount = 1.5 } },
    @{ case = "summary.fileCount als Wahrheitswert"; mutate = { param($m) $m.summary.fileCount = $true } },
    @{ case = "fehlendem Bundle-fileCount"; mutate = { param($m) (Get-LibBundle $m).PSObject.Properties.Remove("fileCount") } },
    @{ case = "Bundle-fileCount als Text"; mutate = { param($m) (Get-LibBundle $m).fileCount = "1" } },
    @{ case = "negativem Bundle-fileCount"; mutate = { param($m) (Get-LibBundle $m).fileCount = -1 } },
    @{ case = "gebrochenem Bundle-fileCount"; mutate = { param($m) (Get-LibBundle $m).fileCount = 1.5 } },
    @{ case = "zu grossem summary.fileCount"; mutate = { param($m) $m.summary.fileCount = 3 } },
    @{ case = "zu kleinem summary.fileCount"; mutate = { param($m) $m.summary.fileCount = 1 } },
    @{ case = "zu grossem Bundle-fileCount"; mutate = { param($m) (Get-LibBundle $m).fileCount = 2 } },
    @{ case = "zu kleinem Bundle-fileCount"; mutate = { param($m) (Get-LibBundle $m).fileCount = 0 } },
    @{ case = "entferntem lib-Eintrag bei unveraenderten Zaehlern"; mutate = { param($m) Remove-ManifestFile $m 'lib/dep.jar' } },
    @{ case = "zusaetzlichem lib-Eintrag bei unveraenderten Zaehlern"; mutate = { param($m) Add-ManifestFile $m 'lib/extra.jar' 'third-party-dependencies' } }
  ) {
    $fixture = New-ValidationFixture ("invalid-" + (Get-LabelSha256 $case).Substring(0, 12)) $mutate

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -Match "Manifest ungueltig"
    $result.Failure | Should -Match "Abbruch vor jedem Download, jedem Loeschen und jedem Stop"
    $result.Output | Should -Not -Match "DEPLOY SUCCESS"
    # Nur das Manifest selbst wurde geladen; kein Bundle, keine Anwendung
    $ghLines = @(Get-GhLogLines $fixture.GhLog)
    $ghLines.Count | Should -Be 1
    $ghLines[0] | Should -Match "--pattern deployment-manifest.json"
    # Kein Stop, kein Loeschen; Cache, Staging und Releases wurden nie angelegt
    ($global:UpdaterCalls -join ",") | Should -Be ""
    foreach ($folder in @("cache", "_staging", "releases")) {
      Test-Path -LiteralPath (Join-Path $fixture.Root $folder) | Should -Be $false
    }
    Test-Path -LiteralPath (Join-Path $fixture.Victim "keep.txt") -PathType Leaf | Should -Be $true
    Test-Path -LiteralPath (Join-Path $fixture.Root "current\sentinel.txt") -PathType Leaf | Should -Be $true
  }

  It "names the duplicate file path and the rule when two bundles share a path" {
    $fixture = New-ValidationFixture "duplicate-named" { param($m) Add-ManifestFile $m 'lib/Dep.jar' 'internal-dependencies' }

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -Match "Manifest ungueltig: files\[\]\.path \(doppelt\) 'lib/Dep\.jar'"
    $result.Failure | Should -Match "Abbruch vor jedem Download, jedem Loeschen und jedem Stop"
    @(Get-GhLogLines $fixture.GhLog).Count | Should -Be 1
    ($global:UpdaterCalls -join ",") | Should -Be ""
  }

  It "names the rule when a library entry sits under application/ (<case>)" -ForEach @(
    @{ case = "Klasse third-party"; mutate = { param($m) (Get-LibFile $m).path = 'application/dep.jar' }; expected = "Manifest ungueltig: files\[\]\.path \(Bibliothek der Klasse third-party-dependencies nur unter lib/\) 'application/dep\.jar'" },
    @{ case = "Klasse internal"; mutate = { param($m) Add-ManifestFile $m 'application/internal.jar' 'internal-dependencies' }; expected = "Manifest ungueltig: files\[\]\.path \(Bibliothek der Klasse internal-dependencies nur unter lib/\) 'application/internal\.jar'" }
  ) {
    $fixture = New-ValidationFixture ("rule-lib-" + (Get-LabelSha256 $case).Substring(0, 12)) $mutate

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -Match $expected
    @(Get-GhLogLines $fixture.GhLog).Count | Should -Be 1
    ($global:UpdaterCalls -join ",") | Should -Be ""
  }

  It "names the rule when the application entry deviates from applicationArtifact.path" {
    $fixture = New-ValidationFixture "rule-application-path" { param($m) (Get-ApplicationFile $m).path = 'application/other.jar' }

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -Match "Manifest ungueltig: files\[\]\.path \(Anwendung nur als applicationArtifact\.path 'application/app\.jar'\) 'application/other\.jar'"
    @(Get-GhLogLines $fixture.GhLog).Count | Should -Be 1
    ($global:UpdaterCalls -join ",") | Should -Be ""
  }

  It "catches an application path in a library entry through the seeded duplicate set even without an application entry" {
    $fixture = New-ValidationFixture "rule-seed" {
      param($m)
      Remove-ApplicationFile $m
      Add-ManifestFile $m 'application/app.jar' 'internal-dependencies'
    }

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -Match "Manifest ungueltig: files\[\]\.path \(doppelt\) 'application/app\.jar'"
    @(Get-GhLogLines $fixture.GhLog).Count | Should -Be 1
    ($global:UpdaterCalls -join ",") | Should -Be ""
  }

  It "requires exactly one application entry in files (<case>)" -ForEach @(
    @{ case = "none"; mutate = { param($m) Remove-ApplicationFile $m }; found = 0 },
    @{ case = "two"; mutate = { param($m) Add-ManifestFile $m 'application/app.jar' 'application' }; found = 2 }
  ) {
    $fixture = New-ValidationFixture ("rule-exactly-one-" + $case) $mutate

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -Match "Manifest ungueltig: files\[\]\.classification \(genau ein Eintrag application erwartet\) 'gefunden: $found'"
    @(Get-GhLogLines $fixture.GhLog).Count | Should -Be 1
    ($global:UpdaterCalls -join ",") | Should -Be ""
  }

  It "names the counter and the files[] count when a file count does not match files[] (<case>)" -ForEach @(
    @{ case = "Bundle-fileCount zu gross"; mutate = { param($m) (Get-LibBundle $m).fileCount = 2 }; expected = "Manifest ungueltig: third-party-dependencies\.fileCount \(files\[\] enthaelt 1\) '2'" },
    @{ case = "Bundle-fileCount zu klein"; mutate = { param($m) (Get-LibBundle $m).fileCount = 0 }; expected = "Manifest ungueltig: third-party-dependencies\.fileCount \(files\[\] enthaelt 1\) '0'" },
    @{ case = "lib-Eintrag entfernt"; mutate = { param($m) Remove-ManifestFile $m 'lib/dep.jar' }; expected = "Manifest ungueltig: third-party-dependencies\.fileCount \(files\[\] enthaelt 0\) '1'" },
    @{ case = "Gesamtzaehler zu gross bei stimmigen Bundle-Zaehlern"; mutate = { param($m) $m.summary.fileCount = 3 }; expected = "Manifest ungueltig: summary\.fileCount \(files\[\] enthaelt 2\) '3'" },
    @{ case = "Gesamtzaehler zu klein bei stimmigen Bundle-Zaehlern"; mutate = { param($m) $m.summary.fileCount = 1 }; expected = "Manifest ungueltig: summary\.fileCount \(files\[\] enthaelt 2\) '1'" }
  ) {
    $fixture = New-ValidationFixture ("rule-count-" + (Get-LabelSha256 $case).Substring(0, 12)) $mutate

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -Match $expected
    $result.Failure | Should -Match "Abbruch vor jedem Download, jedem Loeschen und jedem Stop"
    @(Get-GhLogLines $fixture.GhLog).Count | Should -Be 1
    ($global:UpdaterCalls -join ",") | Should -Be ""
  }

  It "rejects an invalid manifest in a dry run too" {
    $fixture = New-ValidationFixture "invalid-dry-run" { param($m) (Get-LibBundle $m).contentSha256 = '..\..\_deploy' }

    $result = Invoke-ValidationUpdater $fixture -DryRun

    $result.Failure | Should -Match "Manifest ungueltig"
    $result.Output | Should -Not -Match "Remote manifest:"
    Test-Path -LiteralPath (Join-Path $fixture.Root "cache") | Should -Be $false
  }

  It "still deploys a valid manifest with forward-slash and backslash file paths" {
    $fixture = New-ValidationFixture "valid" { param($m) (Get-LibFile $m).path = 'lib\dep.jar' } -WithCache

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -BeNullOrEmpty
    $result.Output | Should -Match "DEPLOY SUCCESS"
    ($global:UpdaterCalls -join ",") | Should -Be "Stop svc,Start svc"
    Test-Path -LiteralPath (Join-Path $fixture.Root "current\lib\dep.jar") -PathType Leaf | Should -Be $true
    Test-Path -LiteralPath (Join-Path $fixture.Victim "keep.txt") -PathType Leaf | Should -Be $true
  }

  It "verifies the staged release once more before the stop and rejects an extra file in staged lib" {
    $fixture = New-ValidationFixture "stage-extra" -WithCache
    # Die Attrappe legt nach dem Kopieren des Manifests eine fremde Datei in lib des Stagings ab
    function global:Copy-Item {
      param([string]$LiteralPath, [string]$Destination, [switch]$Force)
      Microsoft.PowerShell.Management\Copy-Item -LiteralPath $LiteralPath -Destination $Destination -Force:$Force
      if ($Destination -like "*_staging*deployment-manifest.json") {
        Set-Content -LiteralPath (Join-Path (Split-Path -Parent $Destination) "lib\extra.jar") -Value "extra" -Encoding ASCII
      }
    }
    try {
      $result = Invoke-ValidationUpdater $fixture
    } finally {
      Microsoft.PowerShell.Management\Remove-Item Function:\Copy-Item -Force -ErrorAction SilentlyContinue
    }

    $result.Failure | Should -Match "Abschlusspruefung Staging"
    $result.Failure | Should -Match "extra\.jar"
    $result.Failure | Should -Match "Abbruch vor dem Stop"
    $result.Output | Should -Not -Match "DEPLOY SUCCESS"
    # Weder gestoppt noch getauscht: der alte Stand bleibt unberuehrt
    ($global:UpdaterCalls -join ",") | Should -Not -Match "Stop svc"
    Test-Path -LiteralPath (Join-Path $fixture.Root "current\sentinel.txt") -PathType Leaf | Should -Be $true
    Test-Path -LiteralPath (Join-Path $fixture.Root "current\lib") | Should -Be $false
  }

  It "keeps a guard file outside the cache when contentSha256 points there" {
    # Mit vollstaendigem Cache erreichte ein ungeprueftes Manifest das rekursive Loeschen von _deploy
    $fixture = New-ValidationFixture "victim" { param($m) (Get-LibBundle $m).contentSha256 = '..\..\_deploy' } -WithCache

    $result = Invoke-ValidationUpdater $fixture

    Test-Path -LiteralPath (Join-Path $fixture.Victim "keep.txt") -PathType Leaf | Should -Be $true
    $result.Failure | Should -Match "Manifest ungueltig: third-party-dependencies\.contentSha256"
    ($global:UpdaterCalls -join ",") | Should -Be ""
  }

  It "still refreshes a stale cache directory below the cache root" {
    $fixture = New-ValidationFixture "refresh" {
      param($m)
      $source = Join-Path $TestDrive "refresh-src\lib"
      New-Item -ItemType Directory -Force -Path $source | Out-Null
      Set-Content -LiteralPath (Join-Path $source "dep.jar") -Value "third-party dependency" -Encoding ASCII
      $zip = Join-Path $TestDrive "third.zip"
      Compress-Archive -Path (Join-Path $TestDrive "refresh-src\*") -DestinationPath $zip
      $bundle = Get-LibBundle $m
      $bundle.sha256 = Get-TestSha256 $zip
      $bundle.artifactSha256 = Get-TestSha256 $zip
      $bundle.size = (Get-Item -LiteralPath $zip).Length
      $bundle.release = "tag-1"
    } -WithCache
    Copy-Item -LiteralPath (Join-Path $TestDrive "third.zip") -Destination $fixture.Store
    $cachedFile = Join-Path $fixture.Root ("cache\" + (Get-LabelSha256 "v-third") + "\lib\dep.jar")
    Set-Content -LiteralPath $cachedFile -Value "stale content" -Encoding ASCII

    $result = Invoke-ValidationUpdater $fixture

    $result.Failure | Should -BeNullOrEmpty
    $result.Output | Should -Match "DEPLOY SUCCESS"
    Get-Content -Raw -LiteralPath $cachedFile | Should -Match "third-party dependency"
    ($global:UpdaterCalls -join ",") | Should -Match ("Remove [^,]*" + (Get-LabelSha256 "v-third"))
  }

  It "refuses a second run while the run lock is held (DryRun=<dryRun>) and runs after release" -ForEach @(@{ dryRun = $true }, @{ dryRun = $false }) {
    $fixture = New-ValidationFixture "lock-$dryRun" -WithCache
    # Ein anderer Lauf haelt die Sperre: exklusives Handle auf <Dienstordner>\.deploy.lock
    $handle = [System.IO.File]::Open($fixture.Lock, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    try {
      $blocked = Invoke-ValidationUpdater $fixture -DryRun:$dryRun
    } finally {
      $handle.Dispose()
    }

    $blocked.Failure | Should -Match "Laufsperre"
    $blocked.Failure | Should -Match ([regex]::Escape($fixture.Lock))
    $blocked.Failure | Should -Match "ein anderer Updater-Lauf fuer diesen Dienst ist aktiv, Abbruch vor jeder Aenderung"
    @(Get-GhLogLines $fixture.GhLog).Count | Should -Be 0
    ($global:UpdaterCalls -join ",") | Should -Be ""
    Test-Path -LiteralPath (Join-Path $fixture.Root "_downloads") | Should -Be $false

    $released = Invoke-ValidationUpdater $fixture -DryRun:$dryRun

    $released.Failure | Should -BeNullOrEmpty
    if ($dryRun) {
      $released.Output | Should -Match "Remote manifest:"
    } else {
      $released.Output | Should -Match "DEPLOY SUCCESS"
    }
  }

  It "releases the run lock after success and after failure and keeps it out of current" {
    $fixture = New-ValidationFixture "lock-release" -WithCache

    $success = Invoke-ValidationUpdater $fixture
    $success.Output | Should -Match "DEPLOY SUCCESS"
    Test-Path -LiteralPath (Join-Path $fixture.Root "current\.deploy.lock") | Should -Be $false
    $handle = [System.IO.File]::Open($fixture.Lock, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $handle.Dispose()

    $invalid = New-ValidationFixture "lock-release" { param($m) (Get-LibBundle $m).contentSha256 = 'bad' }
    $failure = Invoke-ValidationUpdater $invalid
    $failure.Failure | Should -Match "Manifest ungueltig"
    $handle = [System.IO.File]::Open($fixture.Lock, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $handle.Dispose()
  }
}

Describe "incremental updater final staging verification" {
  BeforeAll {
    # Die Abschlusspruefung samt der Hash-Helfer wird per AST aus dem Updater gelesen und je Test eingebunden
    $script:StagedCheckFunctions = @(
      "Get-Sha256", "Sort-Ordinal", "Add-ContentText", "Add-ContentStream", "Add-NormalizedManifestContent",
      "Get-JarContentSha256", "Get-DeploymentSha256", "Assert-StagedReleaseComplete"
    )
    $found = Get-UpdaterFunctions $script:StagedCheckFunctions
    $script:StagedCheckSource = ($found | ForEach-Object { $_.Extent.Text }) -join "`n"
    $script:StagedCheckFound = @($found | ForEach-Object { $_.Name })

    function New-ManifestFileEntry([string]$Path, [string]$Classification, [string]$FullPath, [string]$Sha256) {
      [pscustomobject]@{ path = $Path; fileName = (Split-Path -Leaf $FullPath); classification = $Classification; sha256 = $Sha256; size = (Get-Item -LiteralPath $FullPath).Length }
    }

    # Staging mit application\app.jar, lib\a.jar (Drittanbieter) und lib\b.jar (Drittanbieter, Pfad mit Backslash im Manifest)
    function New-StagedFixture([string]$Name) {
      $staging = Join-Path $TestDrive $Name
      New-Item -ItemType Directory -Force -Path (Join-Path $staging "application"), (Join-Path $staging "lib") | Out-Null
      $app = Join-Path $staging "application\app.jar"
      $libA = Join-Path $staging "lib\a.jar"
      $libB = Join-Path $staging "lib\b.jar"
      Set-Content -LiteralPath $app -Value "application" -Encoding ASCII
      Set-Content -LiteralPath $libA -Value "library a" -Encoding ASCII
      Set-Content -LiteralPath $libB -Value "library b" -Encoding ASCII
      $appEntry = New-ManifestFileEntry "application/app.jar" "application" $app (Get-TestSha256 $app)
      $manifest = [pscustomobject]@{
        applicationArtifact = [pscustomobject]@{ path = "application/app.jar"; fileName = "app.jar"; sha256 = $appEntry.sha256; size = $appEntry.size }
        files = @(
          $appEntry,
          (New-ManifestFileEntry "lib/a.jar" "third-party-dependencies" $libA (Get-TestSha256 $libA)),
          (New-ManifestFileEntry "lib\b.jar" "third-party-dependencies" $libB (Get-TestSha256 $libB))
        )
      }
      @{ Staging = $staging; Manifest = $manifest; App = $app; LibA = $libA; LibB = $libB }
    }
  }

  It "finds the verification function in the updater" {
    $script:StagedCheckFound | Should -Contain "Assert-StagedReleaseComplete"
  }

  It "accepts a staging that matches the manifest exactly" {
    . ([scriptblock]::Create($script:StagedCheckSource))
    $fixture = New-StagedFixture "final-ok"

    { Assert-StagedReleaseComplete $fixture.Manifest $fixture.Staging } | Should -Not -Throw
  }

  It "rejects a staged library with changed content of the same size" {
    . ([scriptblock]::Create($script:StagedCheckSource))
    $fixture = New-StagedFixture "final-sha"
    Set-Content -LiteralPath $fixture.LibA -Value "library X" -Encoding ASCII

    { Assert-StagedReleaseComplete $fixture.Manifest $fixture.Staging } | Should -Throw "*lib/a.jar*"
  }

  It "rejects a staged library with a different size" {
    . ([scriptblock]::Create($script:StagedCheckSource))
    $fixture = New-StagedFixture "final-size"
    Set-Content -LiteralPath $fixture.LibB -Value "library b but longer" -Encoding ASCII

    { Assert-StagedReleaseComplete $fixture.Manifest $fixture.Staging } | Should -Throw "*b.jar*Groesse*"
  }

  It "rejects a changed staged application artifact" {
    . ([scriptblock]::Create($script:StagedCheckSource))
    $fixture = New-StagedFixture "final-app"
    Set-Content -LiteralPath $fixture.App -Value "application!" -Encoding ASCII

    { Assert-StagedReleaseComplete $fixture.Manifest $fixture.Staging } | Should -Throw "*app.jar*"
  }

  It "rejects a missing staged library" {
    . ([scriptblock]::Create($script:StagedCheckSource))
    $fixture = New-StagedFixture "final-missing"
    Remove-Item -LiteralPath $fixture.LibA

    { Assert-StagedReleaseComplete $fixture.Manifest $fixture.Staging } | Should -Throw "*lib/a.jar*fehlt*"
  }

  It "rejects an extra file in the staged lib folder, also in a subfolder" -ForEach @(
    @{ extra = "extra.jar" },
    @{ extra = "sub\extra.jar" }
  ) {
    . ([scriptblock]::Create($script:StagedCheckSource))
    $fixture = New-StagedFixture ("final-extra-" + (Get-LabelSha256 $extra).Substring(0, 8))
    $extraPath = Join-Path $fixture.Staging ("lib\" + $extra)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $extraPath) | Out-Null
    Set-Content -LiteralPath $extraPath -Value "not in manifest" -Encoding ASCII

    { Assert-StagedReleaseComplete $fixture.Manifest $fixture.Staging } | Should -Throw "*extra.jar*"
  }

  It "compares a snapshot jar by its content hash, not by the raw file hash" {
    . ([scriptblock]::Create($script:StagedCheckSource))
    $fixture = New-StagedFixture "final-snapshot"
    $source = Join-Path $TestDrive "final-snapshot-src"
    New-Item -ItemType Directory -Force -Path (Join-Path $source "META-INF") | Out-Null
    Set-Content -LiteralPath (Join-Path $source "META-INF\MANIFEST.MF") -Value "Manifest-Version: 1.0`nCreated-By: Pester" -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $source "payload.txt") -Value "snapshot library" -Encoding ASCII
    $first = Join-Path $TestDrive "final-snapshot-1.jar"
    Compress-Archive -Path (Join-Path $source "*") -DestinationPath ($first + ".zip")
    Move-Item -LiteralPath ($first + ".zip") -Destination $first
    $expected = Get-DeploymentSha256 $first "snapshot-dependencies"
    # Gleicher Inhalt, andere Zeitstempel im Archiv: die Rohdatei unterscheidet sich, der Inhaltshash nicht
    (Get-Item -LiteralPath (Join-Path $source "payload.txt")).LastWriteTime = (Get-Date).AddDays(-3)
    $second = Join-Path $TestDrive "final-snapshot-2.jar"
    Compress-Archive -Path (Join-Path $source "*") -DestinationPath ($second + ".zip")
    Move-Item -LiteralPath ($second + ".zip") -Destination $second
    (Get-Sha256 $second) | Should -Not -Be (Get-Sha256 $first)
    $expected | Should -Be (Get-DeploymentSha256 $second "snapshot-dependencies")
    $staged = Join-Path $fixture.Staging "lib\snap.jar"
    Copy-Item -LiteralPath $second -Destination $staged
    $fixture.Manifest.files = @($fixture.Manifest.files) + (New-ManifestFileEntry "lib/snap.jar" "snapshot-dependencies" $staged $expected)

    { Assert-StagedReleaseComplete $fixture.Manifest $fixture.Staging } | Should -Not -Throw
  }
}

Describe "incremental updater path guard before recursive delete" {
  BeforeAll {
    $script:PathBelowAst = Get-UpdaterFunctions @("Assert-PathBelow") | Select-Object -First 1
  }

  It "defines Assert-PathBelow in the updater" {
    $script:PathBelowAst | Should -Not -BeNullOrEmpty
  }

  It "accepts <path> strictly below the parent" -ForEach @(
    @{ path = "abc" },
    @{ path = "abc\def" },
    @{ path = "abc\..\def" }
  ) {
    . ([scriptblock]::Create($script:PathBelowAst.Extent.Text))
    $parent = Join-Path $TestDrive "svc\cache"
    { Assert-PathBelow (Join-Path $parent $path) $parent } | Should -Not -Throw
  }

  It "rejects <path> that is not strictly below the parent" -ForEach @(
    @{ path = "" },
    @{ path = "." },
    @{ path = ".." },
    @{ path = "..\..\_deploy" },
    @{ path = "abc\..\..\x" },
    @{ path = "..\cache-evil\x" }
  ) {
    . ([scriptblock]::Create($script:PathBelowAst.Extent.Text))
    $parent = Join-Path $TestDrive "svc\cache"
    $target = if ($path) { Join-Path $parent $path } else { $parent }
    { Assert-PathBelow $target $parent } | Should -Throw "*nicht strikt unterhalb*"
  }

  It "stops Ensure-BundleCache before deleting a path outside the cache, even without the manifest validation" {
    $source = (Get-UpdaterFunctions @("Assert-PathBelow", "Test-BundleCacheMatchesManifest", "Ensure-BundleCache") | ForEach-Object { $_.Extent.Text }) -join "`n"
    . ([scriptblock]::Create($source))
    $base = Join-Path $TestDrive "guard"
    $cache = Join-Path $base "svc\cache"
    $victim = Join-Path $base "_deploy"
    New-Item -ItemType Directory -Force -Path $cache, $victim | Out-Null
    Set-Content -LiteralPath (Join-Path $victim "keep.txt") -Value "keep" -Encoding ASCII
    $bundle = [pscustomobject]@{ classification = "third-party-dependencies"; contentSha256 = '..\..\_deploy' }
    $manifest = [pscustomobject]@{ files = @([pscustomobject]@{ path = "lib/dep.jar"; classification = "third-party-dependencies"; sha256 = "x"; size = 1 }) }

    { Ensure-BundleCache $manifest $bundle $cache $base } | Should -Throw "*nicht strikt unterhalb*"
    Test-Path -LiteralPath (Join-Path $victim "keep.txt") -PathType Leaf | Should -Be $true
  }

  It "guards every recursive Remove-Item with Assert-PathBelow on the line before" {
    $lines = @(Get-Content -LiteralPath $script:UpdaterPath)
    $recursive = 0
    for ($i = 0; $i -lt $lines.Count; $i++) {
      if ($lines[$i] -match '\bRemove-Item\b' -and $lines[$i] -match '-Recurse') {
        $recursive++
        $lines[$i - 1] | Should -Match 'Assert-PathBelow'
      }
    }
    $recursive | Should -BeGreaterThan 0
  }
}
