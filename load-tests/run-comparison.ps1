# Equivalente PowerShell de run-comparison.sh.
#   .\load-tests\run-comparison.ps1 -Scenario medium -Repetitions 3
#   .\load-tests\run-comparison.ps1 -Scenario all -Repetitions 2
# Requiere mock (8090), webflux (8081) y virtual-threads (8082) levantados SIN el perfil demo.
param(
    [string]$Scenario = "medium",
    [int]$Repetitions = 3,
    [string]$K6 = "k6",
    [string]$WebfluxUrl = "http://localhost:8081",
    [string]$VtUrl = "http://localhost:8082",
    [string]$MockUrl = "http://localhost:8090",
    [string]$Warmup = "30s"
)

$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "financial-summary.js"
$session = Get-Date -Format "yyyyMMdd-HHmmss"
$resultsDir = Join-Path $PSScriptRoot "results\$session"
New-Item -ItemType Directory -Force $resultsDir | Out-Null

$scenarios = if ($Scenario -eq "all") { "low", "medium", "high", "slow-downstream", "failing-downstream", "stress" } else { @($Scenario) }
$targets = @{ "webflux" = $WebfluxUrl; "virtual-threads" = $VtUrl }

function Invoke-K6([string]$label, [string]$scenarioName, [string]$runId, [string]$duration) {
    $k6Args = @("run", "-q", "-e", "BASE_URL=$($targets[$label])", "-e", "LABEL=$label", "-e", "SCENARIO=$scenarioName",
        "-e", "MOCK_URL=$MockUrl", "-e", "RESULTS_DIR=$resultsDir", "-e", "RUN_ID=$runId")
    if ($duration) { $k6Args += @("-e", "DURATION=$duration") }
    & $K6 @k6Args $script
}

foreach ($s in $scenarios) {
    for ($rep = 1; $rep -le $Repetitions; $rep++) {
        $order = if ($rep % 2 -eq 1) { "webflux", "virtual-threads" } else { "virtual-threads", "webflux" }
        foreach ($label in $order) {
            if ($Warmup -ne "0") {
                Write-Host ">> calentamiento $label ($Warmup)"
                Invoke-K6 $label "warmup" "warmup-$s-r$rep" $Warmup | Out-Null
            }
            Write-Host ">> $s | repetición $rep/$Repetitions | $label"
            Invoke-K6 $label $s "r$rep" ""
            Start-Sleep -Seconds 5
        }
    }
}

$summary = Join-Path $resultsDir "summary.csv"
$csvs = Get-ChildItem "$resultsDir\*.csv" | Where-Object { $_.Name -notlike "*-warmup-*" -and $_.Name -ne "summary.csv" }
$csvs | ForEach-Object { Import-Csv $_.FullName } | Export-Csv $summary -NoTypeInformation -Encoding utf8
Write-Host "`nResultados: $summary"
Import-Csv $summary | Format-Table label, scenario, run_id, rps, avg_ms, p50_ms, p95_ms, p99_ms, error_rate, timeouts, cpu_avg_pct, heap_max_mb -AutoSize
