$ErrorActionPreference = 'Stop'
$state = Join-Path $env:OMNISTREAM_HOME 'state'
$log = Join-Path $state 'logs'
$stopFlag = Join-Path $state 'stop.request'
$urlFile = Join-Path $state 'console.url'
$pgdata = Join-Path $state 'postgres'
$serviceJar = Join-Path $PSScriptRoot 'service.jar'
$lease = $null
$postgresStarted = $false
$children = @()
$exitCode = 0

function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
    foreach ($vendor in @('Java', 'Eclipse Adoptium', 'Microsoft', 'Amazon Corretto')) {
        $directory = Join-Path $env:ProgramFiles $vendor
        if (Test-Path -LiteralPath $directory) {
            $candidates += @(Get-ChildItem -LiteralPath $directory -Directory |
                ForEach-Object { Join-Path $_.FullName 'bin\java.exe' })
        }
    }
    $fromPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($fromPath) { $candidates += $fromPath.Source }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (!(Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        # Vendor shims on PATH may not have a release file. Query the binary itself.
        $versionInfo = [Diagnostics.ProcessStartInfo]::new($candidate, '-version')
        $versionInfo.UseShellExecute = $false
        $versionInfo.RedirectStandardError = $true
        $versionInfo.CreateNoWindow = $true
        $process = [Diagnostics.Process]::Start($versionInfo)
        $version = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $process.Dispose()
        if ($version -match 'version "(\d+)' -and [int]$Matches[1] -ge 21) { return $candidate }
    }
    throw 'Java 21 or newer is required. Install a JDK and set JAVA_HOME to its directory.'
}

function Find-Postgres {
    $bins = @()
    if ($env:OMNISTREAM_PG_BIN) { $bins += $env:OMNISTREAM_PG_BIN }
    $installed = Join-Path $env:ProgramFiles 'PostgreSQL'
    if (Test-Path -LiteralPath $installed) {
        $bins += @(Get-ChildItem -LiteralPath $installed -Directory | Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin' })
    }
    $fromPath = Get-Command initdb.exe -ErrorAction SilentlyContinue
    if ($fromPath) { $bins += Split-Path $fromPath.Source }
    foreach ($dir in $bins) {
        $init = Join-Path $dir 'initdb.exe'
        if ((Test-Path -LiteralPath $init) -and ((& $init --version) -match 'PostgreSQL\)\s+(\d+)') -and [int]$Matches[1] -ge 16) { return $dir }
    }
    throw 'Install PostgreSQL 16 or newer for Windows, or set OMNISTREAM_PG_BIN to its bin directory.'
}

function Free-Port([int]$preferred, [int[]]$exclude) {
    while ($true) {
        $socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $preferred)
        try { $socket.Start(); $port = $socket.LocalEndpoint.Port }
        catch { if ($preferred -eq 0) { throw }; $preferred = 0; continue }
        finally { $socket.Stop() }
        if ($exclude -notcontains $port) { return $port }
        $preferred = 0
    }
}

function Start-Java([string]$name, [string[]]$arguments) {
    $quoted = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $child = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $state -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $log "$name.out.log") -RedirectStandardError (Join-Path $log "$name.err.log")
    $marker = @{ id = $child.Id; ticks = $child.StartTime.ToUniversalTime().Ticks; executable = $java }
    $marker | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $state "$name.pid.json")
    return $child
}

function Stop-OwnedChild([string]$name) {
    $record = Join-Path $state "$name.pid.json"
    if (!(Test-Path -LiteralPath $record)) { return }
    $marker = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
    $child = Get-Process -Id $marker.id -ErrorAction SilentlyContinue
    if ($child -and $child.StartTime.ToUniversalTime().Ticks -eq $marker.ticks -and $child.Path -eq $marker.executable) {
        $child.Kill()
        $null = $child.WaitForExit(10000)
    }
    Remove-Item -LiteralPath $record
}

function Open-Console([string]$url) {
    Write-Host "Omnistream is ready at $url"
    Write-Host "The console connects automatically. Open Help for a guided example."
    if ($env:OMNISTREAM_NO_BROWSER -ne '1') { Start-Process $url }
}

try {
    [void][IO.Directory]::CreateDirectory($log)
    try {
        $lease = [IO.File]::Open((Join-Path $state 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    } catch [IO.IOException] {
        if (Test-Path -LiteralPath $urlFile) { Open-Console ((Get-Content -LiteralPath $urlFile -Raw).Trim()) }
        else { Write-Host 'Omnistream is already starting. See its open console window.' }
        exit 0
    }
    foreach ($file in @($urlFile, $stopFlag)) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
    $java = Find-Java
    $pg = Find-Postgres
    Write-Host "Service runtime: $java"
    Write-Host "PostgreSQL tools: $pg"
    Write-Host "Startup logs: $log"
    Stop-OwnedChild 'app'
    Stop-OwnedChild 'broker'
    $mode = $env:PARSER_MODE
    if (!$mode) { $mode = 'groq' }
    $groqKey = $env:GROQ_API_KEY
    Remove-Item Env:\GROQ_API_KEY -ErrorAction SilentlyContinue
    if ($mode -eq 'groq' -and !$groqKey) {
        $credentialPath = Join-Path $env:LOCALAPPDATA 'Omnistream\groq.credential'
        if (Test-Path -LiteralPath $credentialPath) {
            try {
                $secret = ([IO.File]::ReadAllText($credentialPath)).Trim() | ConvertTo-SecureString
                try { $groqKey = ([pscredential]::new('groq', $secret)).GetNetworkCredential().Password }
                finally { $secret.Dispose() }
            } catch { Write-Warning 'Saved Groq credential could not be decrypted for this Windows user.' }
        }
        if (!$groqKey) {
            $mode = 'local'
            Write-Host 'Using the local rules parser. Open Help for Groq setup.'
        }
    }
    Write-Host "Parser: $mode"
    $appPort = Free-Port 8080 @()
    $dbPort = Free-Port 0 @($appPort)
    $brokerPort = Free-Port 0 @($appPort,$dbPort)
    $controllerPort = Free-Port 0 @($appPort,$dbPort,$brokerPort)
    $url = "http://127.0.0.1:$appPort"

    $libs = Join-Path $PSScriptRoot 'kafka_2.13-3.9.1\libs\*'
    $installed = Join-Path $PSScriptRoot 'kafka.ready'
    if (!(Test-Path -LiteralPath $installed)) {
        Write-Host 'Unpacking the bundled Kafka distribution...'
        & tar.exe -xzf (Join-Path $PSScriptRoot 'kafka.tgz') -C $PSScriptRoot
        if ($LASTEXITCODE -ne 0) { throw 'Could not unpack Kafka.' }
        [IO.File]::WriteAllText($installed, '3.9.1')
    }

    $passFile = Join-Path $state 'postgres.credential'
    if (!(Test-Path -LiteralPath $passFile)) {
        if (Test-Path -LiteralPath (Join-Path $pgdata 'PG_VERSION')) { throw "Existing database credential is missing at $passFile." }
        $password = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
        ConvertFrom-SecureString (ConvertTo-SecureString $password -AsPlainText -Force) | Set-Content -LiteralPath $passFile
    } else {
        $password = ([pscredential]::new('omnistream', ((Get-Content -LiteralPath $passFile -Raw).Trim() | ConvertTo-SecureString))).GetNetworkCredential().Password
    }
    if (!(Test-Path -LiteralPath (Join-Path $pgdata 'PG_VERSION'))) {
        Write-Host 'Initializing the private PostgreSQL database...'
        $tempPassword = Join-Path $state ([guid]::NewGuid().ToString() + '.tmp')
        try {
            [IO.File]::WriteAllText($tempPassword, $password)
            & (Join-Path $pg 'initdb.exe') -D $pgdata -U omnistream -A scram-sha-256 --encoding=UTF8 "--pwfile=$tempPassword" | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initialization failed.' }
        } finally { if (Test-Path -LiteralPath $tempPassword) { Remove-Item -LiteralPath $tempPassword } }
    }
    $pgctl = Join-Path $pg 'pg_ctl.exe'
    & $pgctl -D $pgdata status | Out-Null
    if ($LASTEXITCODE -eq 0) {
        & $pgctl -D $pgdata -w stop -m fast | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'The previous Omnistream database could not be stopped.' }
    }
    # Wait for pg_ctl itself. A PowerShell pipeline can remain open through postgres.exe.
    $dbArguments = @('-D',$pgdata,'-l',(Join-Path $log 'postgres.log'),'-o',"-p $dbPort -h 127.0.0.1",'-w','start')
    $dbCommand = ($dbArguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $control = Start-Process -FilePath $pgctl -ArgumentList $dbCommand -WindowStyle Hidden -PassThru
    $control.WaitForExit()
    if ($control.ExitCode -ne 0) { throw 'PostgreSQL failed to start. See postgres.log.' }
    $postgresStarted = $true

    $props = Join-Path $state 'kraft.properties'
    @(
        'process.roles=broker,controller', 'node.id=1', "controller.quorum.voters=1@127.0.0.1:$controllerPort",
        "listeners=PLAINTEXT://127.0.0.1:$brokerPort,CONTROLLER://127.0.0.1:$controllerPort",
        "advertised.listeners=PLAINTEXT://127.0.0.1:$brokerPort", 'controller.listener.names=CONTROLLER',
        'listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
        'log.dirs=broker-data', 'num.partitions=3', 'offsets.topic.replication.factor=1',
        'transaction.state.log.replication.factor=1', 'transaction.state.log.min.isr=1', 'group.initial.rebalance.delay.ms=0'
    ) | Set-Content -LiteralPath $props -Encoding ascii
    if (!(Test-Path -LiteralPath (Join-Path $state 'broker-data\meta.properties'))) {
        Push-Location $state
        try {
            $clusterId = [Convert]::ToBase64String([guid]::NewGuid().ToByteArray()).TrimEnd('=').Replace('+','-').Replace('/','_')
            & $java -cp $libs kafka.tools.StorageTool format -t $clusterId -c $props
            if ($LASTEXITCODE -ne 0) { throw 'Kafka storage initialization failed.' }
        } finally { Pop-Location }
    }
    Write-Host 'Starting Kafka and the ingestion service...'
    $broker = Start-Java 'broker' @('-Xmx384m','-cp',$libs,'kafka.Kafka',$props)
    $children += 'broker'
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:PARSER_MODE = $mode
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:PORT = $appPort
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$dbPort/postgres"
    $env:DB_USER = 'omnistream'
    $env:DB_PASSWORD = $password
    $env:KAFKA_BROKERS = "127.0.0.1:$brokerPort"
    $env:API_KEY = 'omni-local-development'
    if ($mode -eq 'groq') { $env:GROQ_API_KEY = $groqKey }
    $app = Start-Java 'app' @('-jar',$serviceJar)
    $children += 'app'
    Remove-Item Env:\GROQ_API_KEY -ErrorAction SilentlyContinue
    $groqKey = $null
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        if ($broker.HasExited -or $app.HasExited) { throw "A service exited during startup. See app.out.log and broker.err.log in $log." }
        try { $health = Invoke-RestMethod -Uri "$url/actuator/health/readiness" -TimeoutSec 4 } catch { $health = $null }
        if ($health.status -eq 'UP') { break }
        Start-Sleep -Milliseconds 600
    } while ([DateTime]::UtcNow -lt $deadline -and !(Test-Path -LiteralPath $stopFlag))
    if (!(Test-Path -LiteralPath $stopFlag)) {
        if ($health.status -ne 'UP') { throw "Startup timed out. See $log." }
        [IO.File]::WriteAllText($urlFile, $url)
        Open-Console $url
        Write-Host 'Keep this window open. Press Q here to stop all three services.'
        while (!(Test-Path -LiteralPath $stopFlag)) {
            if ($app.HasExited -or $broker.HasExited) { throw "A service stopped. See $log." }
            if (![Console]::IsInputRedirected -and [Console]::KeyAvailable) {
                if ([Console]::ReadKey($true).Key -eq 'Q') { break }
            }
            Start-Sleep -Milliseconds 350
        }
    }
} catch {
    $exitCode = 1
    $message = "Startup error: $($_.Exception.Message)"
    Write-Host $message
    $message | Out-File -LiteralPath (Join-Path $log 'launcher.log') -Append
    if ($env:OMNISTREAM_NO_BROWSER -ne '1') { [void](Read-Host 'Press Enter to close') }
} finally {
    if ($lease) {
        foreach ($name in @('app','broker')) { if ($children -contains $name) { Stop-OwnedChild $name } }
        if ($postgresStarted) { & $pgctl -D $pgdata -m fast -w stop | Out-Host }
        foreach ($file in @($urlFile, $stopFlag)) { if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file } }
        $lease.Dispose()
    }
}
exit $exitCode
