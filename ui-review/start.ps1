$ErrorActionPreference = 'Stop'
$node = (Get-Command node -ErrorAction Stop).Source
try {
    $response = Invoke-RestMethod -Uri 'http://127.0.0.1:4173/api/revision' -TimeoutSec 2
    if ($response.web) { Write-Host 'Review already running: http://127.0.0.1:4173'; exit 0 }
} catch { }
$process = Start-Process -FilePath $node -ArgumentList 'server.mjs' -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru
Write-Host "GenWow review started (PID $($process.Id)): http://127.0.0.1:4173"
Write-Host 'Open that URL in your browser. HTML/CSS/JS edits refresh automatically.'
