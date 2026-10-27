# =============================================================
# build-tar.ps1 - CS OrgChart image for offline deployment
#
#   1) Build the Spring Boot JAR with the Maven wrapper
#   2) Check that the JAR contains OpenSAML (SSO with Microsoft Entra ID)
#   3) Build the Docker image from that JAR (no Maven inside Docker, so
#      the Shibboleth repository is needed only on this machine)
#   4) Save the image to a .tar for "docker load" on the server
#
# Usage:
#   double-click build-tar.cmd
#   powershell -ExecutionPolicy Bypass -File .\build-tar.ps1
#   .\build-tar.ps1 -RunTests
#   .\build-tar.ps1 -ImageTag csorgchart_offline:sso -OutputDir D:\deploy
#
# On the server:
#   docker tag csorgchart_offline:latest csorgchart_offline:before-sso   (keep the old one)
#   docker load -i csorgchart_offline.tar
#   docker compose up -d
# =============================================================

[CmdletBinding()]
param(
    [string]$ImageTag = "csorgchart_offline:latest",
    [string]$ArchiveName = "csorgchart_offline.tar",
    [string]$OutputDir = "",
    [string]$BaseImage = "eclipse-temurin:21-jre-alpine",
    [switch]$RunTests,
    [switch]$SkipMavenBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step([int]$Number, [string]$Message) {
    Write-Host ""
    Write-Host "[$Number] $Message" -ForegroundColor Cyan
    Write-Host ("-" * 70) -ForegroundColor DarkGray
}

function Write-Ok([string]$Message) { Write-Host "[OK] $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "[WARN] $Message" -ForegroundColor Yellow }

function Format-Size([long]$Bytes) {
    $units = @("B", "KB", "MB", "GB")
    $value = [double]$Bytes
    $i = 0
    while ($value -ge 1024 -and $i -lt $units.Length - 1) { $value /= 1024; $i++ }
    return "{0:N1} {1}" -f $value, $units[$i]
}

function Assert-ExitCode([string]$What) {
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $OutputDir) { $OutputDir = $ProjectRoot }
$ArchivePath = Join-Path $OutputDir $ArchiveName
$ImageDir = Join-Path $ProjectRoot "target\docker-image"

Write-Host ""
Write-Host "===============================================" -ForegroundColor DarkCyan
Write-Host " CS OrgChart - offline Docker image" -ForegroundColor White
Write-Host "===============================================" -ForegroundColor DarkCyan
Write-Host "Project:    $ProjectRoot" -ForegroundColor DarkGray
Write-Host "Image:      $ImageTag (base $BaseImage)" -ForegroundColor DarkGray
Write-Host "Archive:    $ArchivePath" -ForegroundColor DarkGray

try {
    & docker version --format "{{.Server.Version}}" *> $null
    Assert-ExitCode "docker version"
}
catch {
    Write-Host "[FAIL] Docker is not running. Start Docker Desktop and try again." -ForegroundColor Red
    exit 1
}

$revision = ""
try {
    $revision = (& git -C $ProjectRoot rev-parse --short HEAD 2>$null)
    $branch = (& git -C $ProjectRoot rev-parse --abbrev-ref HEAD 2>$null)
    if ($revision) { Write-Host "Git:        $branch @ $revision" -ForegroundColor DarkGray }
    $dirty = (& git -C $ProjectRoot status --porcelain --untracked-files=no 2>$null)
    if ($dirty) { Write-Warn "There are uncommitted changes: the image is built from the working copy" }
}
catch {
    $revision = ""
}

try {
    # 1. JAR
    if (-not $SkipMavenBuild) {
        Write-Step 1 ("Maven build" + $(if ($RunTests) { " with tests" } else { " (tests skipped)" }))
        $mvnArgs = @("clean", "package", "-B")
        if (-not $RunTests) { $mvnArgs += "-DskipTests" }
        Push-Location $ProjectRoot
        try {
            & (Join-Path $ProjectRoot "mvnw.cmd") @mvnArgs
            Assert-ExitCode "Maven build"
        }
        finally {
            Pop-Location
        }
        Write-Ok "JAR built"
    }
    else {
        Write-Warn "Maven build skipped (-SkipMavenBuild): using the JAR already in target\"
    }

    $jar = Get-ChildItem (Join-Path $ProjectRoot "target") -Filter "cs-orgchart-*.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "No target\cs-orgchart-*.jar found: run without -SkipMavenBuild" }

    # 2. OpenSAML inside the JAR (comes only from the Shibboleth repository)
    Write-Step 2 "Checking $($jar.Name)"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $libs = @($zip.Entries | Where-Object { $_.FullName -like "BOOT-INF/lib/*.jar" } | ForEach-Object { $_.Name })
    }
    finally {
        $zip.Dispose()
    }
    $openSaml = @($libs | Where-Object { $_ -like "opensaml-*" })
    $javaSupport = @($libs | Where-Object { $_ -like "java-support-*" })
    if ($openSaml.Count -lt 12 -or $javaSupport.Count -lt 1) {
        throw "The JAR has $($openSaml.Count) OpenSAML libraries (expected 12) and $($javaSupport.Count) java-support: SSO would not start. Check that Maven can reach https://build.shibboleth.net/maven/releases/ (docs/SSO.md, section 5.1)"
    }
    Write-Ok "$($libs.Count) libraries, OpenSAML: $($openSaml.Count), java-support: $($javaSupport -join ', ')"
    Write-Host "JAR size: $(Format-Size $jar.Length)" -ForegroundColor DarkGray

    # 3. Image from the JAR
    Write-Step 3 "Docker image $ImageTag"
    if (Test-Path $ImageDir) { Remove-Item $ImageDir -Recurse -Force }
    New-Item -ItemType Directory -Path $ImageDir | Out-Null
    Copy-Item $jar.FullName (Join-Path $ImageDir "app.jar")
    $dockerfile = @"
FROM $BaseImage
RUN addgroup -S -g 10001 appgroup && adduser -S -u 10001 -G appgroup -s /sbin/nologin appuser
WORKDIR /app
COPY --chown=appuser:appgroup app.jar /app/app.jar
RUN chmod 500 /app/app.jar
USER appuser:appgroup
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
"@
    # Docker reads the file as UTF-8 without BOM
    [System.IO.File]::WriteAllText((Join-Path $ImageDir "Dockerfile"), $dockerfile, (New-Object System.Text.UTF8Encoding($false)))

    $buildArgs = @("build", "-t", $ImageTag, "--label", "org.opencontainers.image.title=cs-orgchart")
    if ($revision) { $buildArgs += @("--label", "org.opencontainers.image.revision=$revision") }
    $buildArgs += $ImageDir
    & docker @buildArgs
    Assert-ExitCode "docker build"
    Write-Ok "Image built: $ImageTag"

    # 4. tar
    Write-Step 4 "Saving to $ArchivePath"
    if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null }
    if (Test-Path $ArchivePath) { Remove-Item $ArchivePath -Force }
    & docker save -o $ArchivePath $ImageTag
    Assert-ExitCode "docker save"
    $archive = Get-Item $ArchivePath
    $hash = (Get-FileHash $ArchivePath -Algorithm SHA256).Hash.ToLower()
    Write-Ok "Saved: $(Format-Size $archive.Length)"
    Write-Host "SHA256: $hash" -ForegroundColor DarkGray
}
catch {
    Write-Host ""
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "===============================================" -ForegroundColor DarkCyan
Write-Host " Done: $ArchivePath" -ForegroundColor Green
Write-Host "===============================================" -ForegroundColor DarkCyan
Write-Host "On the server (WSL):" -ForegroundColor Yellow
$imageName = $ImageTag -replace ':[^:/]+$', ''
Write-Host "    docker tag $ImageTag ${imageName}:before-sso    # keep the running image for rollback" -ForegroundColor White
Write-Host "    docker load -i $ArchiveName" -ForegroundColor White
Write-Host "    sha256sum $ArchiveName                        # must be $hash" -ForegroundColor White
Write-Host "    docker compose up -d" -ForegroundColor White
Write-Host "The new image needs SPRING_PROFILES_ACTIVE and the SSO variables: docs/SSO.md, section 4." -ForegroundColor Yellow
Write-Host ""
exit 0
