# Arranca mock (8090), WebFlux (8081) y Virtual Threads (8082) con los MISMOS flags de JVM.
#   .\scripts\start-all.ps1                 # modo benchmark (logs INFO)
#   .\scripts\start-all.ps1 -SpringProfile demo   # modo charla (logs DEBUG + endpoints de demo)
#   .\scripts\start-all.ps1 -ExtraArgs "--banking.downstream.max-connections=20"   # args extra para ambas APIs
# Logs en .\logs\*.log. Para parar: .\scripts\stop-all.ps1
param([string]$SpringProfile = "", [string]$ExtraArgs = "")

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$version = "1.0.0-SNAPSHOT"
$jvm = @("-Xms512m", "-Xmx512m", "-XX:+UseG1GC")

if (-not (Test-Path "banking-api-webflux\target\banking-api-webflux-$version.jar") -or
    -not (Test-Path "banking-api-virtual-threads\target\banking-api-virtual-threads-$version.jar")) {
    Write-Host ">> Empaquetando (mvn -DskipTests package)..."
    mvn -q -B -DskipTests package
    if (-not $?) { throw "Fallo al compilar" }
}

New-Item -ItemType Directory -Force logs | Out-Null

function Start-LabService([string]$name, [string]$jar, [int]$port, [string[]]$extra) {
    $argList = $jvm + @("-jar", $jar) + $extra
    $p = Start-Process java -ArgumentList $argList -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "logs\$name.log" -RedirectStandardError "logs\$name.err.log"
    Set-Content -Path "logs\$name.pid" -Value $p.Id -Encoding ascii
    for ($i = 0; $i -lt 60; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "http://localhost:$port/actuator/health" | Out-Null
            Write-Host "OK  $name en http://localhost:$port (pid $($p.Id))"
            return
        } catch [System.Net.WebException] {
            # 503 (DOWN) también significa que ya está escuchando
            if ($_.Exception.Response) { Write-Host "OK  $name en http://localhost:$port (pid $($p.Id))"; return }
            Start-Sleep -Seconds 1
        }
    }
    throw "$name no arrancó; revisa logs\$name.log"
}

$profileArg = @()
if ($SpringProfile) { $profileArg = @("--spring.profiles.active=$SpringProfile") }
if ($ExtraArgs) { $profileArg += ($ExtraArgs -split " " | Where-Object { $_ }) }

Start-LabService "downstream-mock-service" "downstream-mock-service\target\downstream-mock-service-$version.jar" 8090 @()
Start-LabService "banking-api-webflux" "banking-api-webflux\target\banking-api-webflux-$version.jar" 8081 $profileArg
Start-LabService "banking-api-virtual-threads" "banking-api-virtual-threads\target\banking-api-virtual-threads-$version.jar" 8082 $profileArg
