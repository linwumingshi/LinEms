# restart-broker.ps1
# Purpose: restart energy-mqtt-broker as ADMINISTRATOR so the OTA TopicAcl fix takes
#   effect and CONNACK is restored (the degraded broker accepts TCP but never sends CONNACK).
# Safety: locate broker ONLY by listening port 18831; never kill java by name (would kill Nacos 8848).
# NOTE: script is ASCII-only on purpose - PowerShell 5.1 parses .ps1 as the system codepage,
#   so UTF-8 (no BOM) Chinese text corrupts parsing. Keep comments/messages in English.

$ErrorActionPreference = 'Stop'

$BROKER_PORT = 18831
$BACKEND_DIR  = 'D:\ProgramData\_GitHub\EnergyStorageIotPlatform\backend'
$JAVA_HOME    = 'D:\Program Files\Java\graalvm-jdk-22.0.1'
$MAVEN        = 'D:\Program Files\Maven\bin\mvn.cmd'
$MVN_REPO     = 'D:\Program Files\maven-repo'

# 1) Find broker PID by listening port (Get-NetTCPConnection avoids netstat text parsing)
$conn = Get-NetTCPConnection -LocalPort $BROKER_PORT -State Listen -ErrorAction SilentlyContinue
if ($conn) {
    $pid = $conn.OwningProcess
    Write-Host "Found broker on 18831, PID=$pid, terminating..."
    & taskkill /PID $pid /F
    if ($LASTEXITCODE -ne 0) { throw "Failed to terminate PID $pid, please run this script as Administrator" }
    $wait = 0
    while ((Get-NetTCPConnection -LocalPort $BROKER_PORT -State Listen -ErrorAction SilentlyContinue) -and $wait -lt 15) {
        Start-Sleep -Seconds 1
        $wait++
    }
} else {
    Write-Host "No listener on 18831, skipping termination (first start?)"
}

# 2) Repackage (offline). Old jar handle is released after the kill, so repackage rename succeeds.
$env:JAVA_HOME = $JAVA_HOME
$env:SERVER__PORT = $null
$env:SERVER__HOST = $null
$env:MAVEN_OPTS = "-Dmaven.repo.local=`"$MVN_REPO`""
Set-Location $BACKEND_DIR
Write-Host "Repackaging energy-mqtt-broker ..."
& $MAVEN -o -pl energy-mqtt-broker package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

# 3) Start with javaw (no window); clear host-injected random-port vars
$jar = "$BACKEND_DIR\energy-mqtt-broker\target\energy-mqtt-broker-1.0.0-SNAPSHOT.jar"
$logOut = "$BACKEND_DIR\energy-mqtt-broker\logs\broker.out.log"
$logErr = "$BACKEND_DIR\energy-mqtt-broker\logs\broker.err.log"
New-Item -ItemType Directory -Force -Path (Split-Path $logOut) | Out-Null
$env:SERVER__PORT = $null
$env:SERVER__HOST = $null
Write-Host "Starting broker (javaw)..."
Start-Process -FilePath "$JAVA_HOME\bin\javaw.exe" -ArgumentList "-jar", """$jar""" -WorkingDirectory $BACKEND_DIR -RedirectStandardOutput $logOut -RedirectStandardError $logErr -WindowStyle Hidden

# 4) Wait for port ready
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    if (Get-NetTCPConnection -LocalPort $BROKER_PORT -State Listen -ErrorAction SilentlyContinue) {
        $ready = $true
        break
    }
    Start-Sleep -Seconds 1
}
if ($ready) {
    Write-Host "Broker restarted and listening on 18831 (ACL fix applied)"
} else {
    Write-Host "Broker start timed out, check $logOut / $logErr"
}
