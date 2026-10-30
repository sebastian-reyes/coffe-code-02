# Detiene los procesos arrancados con start-all.ps1.
Set-Location (Join-Path $PSScriptRoot "..")
Get-ChildItem logs\*.pid -ErrorAction SilentlyContinue | ForEach-Object {
    $processId = [int](Get-Content $_.FullName)
    try {
        Stop-Process -Id $processId -Force -Confirm:$false -ErrorAction Stop
        Write-Host "parado $($_.BaseName) (pid $processId)"
    } catch {
        Write-Host "$($_.BaseName) ya no estaba en ejecución"
    }
    Remove-Item $_.FullName -Confirm:$false
}
