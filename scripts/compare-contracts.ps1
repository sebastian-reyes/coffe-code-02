# Compara status + cuerpo de ambas APIs para los mismos clientes (equivalente a compare-contracts.sh).
#   .\scripts\compare-contracts.ps1
param(
    [string]$WebfluxUrl = "http://localhost:8081",
    [string]$VtUrl = "http://localhost:8082"
)

# HttpClient (y no Invoke-WebRequest): no lanza excepción con 4xx/5xx y permite decodificar en UTF-8.
# Con Invoke-WebRequest en PowerShell 5.1 el cuerpo de un error con respuesta chunked llega vacío.
Add-Type -AssemblyName System.Net.Http
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromSeconds(10)

function Get-Raw([string]$url) {
    try {
        $response = $http.GetAsync($url).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        return @{ Status = [int]$response.StatusCode; Body = [System.Text.Encoding]::UTF8.GetString($bytes) }
    } catch {
        return @{ Status = 0; Body = $_.Exception.Message }
    }
}

$clients = "C0001", "C4321", "C0404", "C0500", "C0504", "C0599", "C0800", "X1"
$failures = 0
foreach ($id in $clients) {
    $path = "/api/clients/$id/financial-summary"
    $a = Get-Raw "$WebfluxUrl$path"
    $b = Get-Raw "$VtUrl$path"
    if ($a.Status -eq $b.Status -and $a.Body -eq $b.Body) {
        $preview = $a.Body.Substring(0, [Math]::Min(90, $a.Body.Length))
        Write-Host ("OK    {0,-6} {1}  {2}" -f $id, $a.Status, $preview)
    } else {
        Write-Host ("DIFF  {0,-6} webflux={1} vt={2}" -f $id, $a.Status, $b.Status) -ForegroundColor Red
        Write-Host "  webflux: $($a.Body)"
        Write-Host "  vt:      $($b.Body)"
        $failures++
    }
}
if ($failures -eq 0) { Write-Host "Contratos equivalentes." -ForegroundColor Green } else { Write-Host "$failures diferencias."; exit 1 }
