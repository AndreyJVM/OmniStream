param(
    [Parameter(Mandatory)][string]$ServiceJar,
    [Parameter(Mandatory)][string]$KafkaArchive,
    [Parameter(Mandatory)][string]$OutputJar
)
$ErrorActionPreference = 'Stop'
$service = (Resolve-Path -LiteralPath $ServiceJar).Path
$kafka = (Resolve-Path -LiteralPath $KafkaArchive).Path
$output = [IO.Path]::GetFullPath($OutputJar)
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 or newer.' }
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$stage = Join-Path $tempRoot ("omnistream-build-" + [guid]::NewGuid().ToString('N'))
try {
    $payload = [IO.Directory]::CreateDirectory((Join-Path $stage 'payload')).FullName
    Copy-Item -LiteralPath $service -Destination (Join-Path $payload 'service.jar')
    Copy-Item -LiteralPath $kafka -Destination (Join-Path $payload 'kafka.tgz')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start.ps1') -Destination $payload
    & (Join-Path $env:JAVA_HOME 'bin\javac.exe') --release 8 -d $stage (Join-Path $PSScriptRoot 'WindowsLauncher.java')
    if ($LASTEXITCODE -ne 0) { throw 'Launcher compilation failed.' }
    & (Join-Path $env:JAVA_HOME 'bin\jar.exe') --create --no-compress --file $output --main-class WindowsLauncher -C $stage WindowsLauncher.class -C $stage payload
    if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }
} finally {
    if (Test-Path -LiteralPath $stage) {
        $resolved = (Resolve-Path -LiteralPath $stage).Path
        if ((Split-Path -Parent $resolved) -ne $tempRoot -or (Split-Path -Leaf $resolved) -notmatch '^omnistream-build-[a-f0-9]{32}$') {
            throw 'Unexpected staging directory.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}