# Starts Project SUTRA: the Python document service, the five Java services and the frontend, each in its own
# window.
#
#   .\start-all.ps1                 the real system on sutra_db: gateway http://localhost:8080, UI http://localhost:5173
#   .\start-all.ps1 -Acceptance     a separate copy for ai\scripts\acceptance_test.py: sutra_test database, gateway on
#                                   :9080 (services 9081-9084), no frontend. Real data is never touched.
#
# Groq reads quotations (Tesseract OCR for scanned PDFs) when the GROQ_API_KEY user environment variable is set
# (see README); without it the
# rule-based reader is used. Stop everything with  .\stop-all.ps1
param([switch]$Acceptance)

$root = $PSScriptRoot
$mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) { "mvn" } else { "C:\tools\apache-maven-3.9.11\bin\mvn.cmd" }

if ($Acceptance) {
    $db = "jdbc:postgresql://localhost:5432/sutra_test"
    $ports = @{ gateway = 9080; auth = 9081; masterdata = 9082; procurement = 9083; audit = 9084 }
    $label = "SUTRA acceptance"
} else {
    $db = "jdbc:postgresql://localhost:5432/sutra_db"
    $ports = @{ gateway = 8080; auth = 8081; masterdata = 8082; procurement = 8083; audit = 8084 }
    $label = "SUTRA"
}

# Every service gets the same addresses for the others, and its own port.
$env:DB_URL = $db
$env:AUTH_URL = "http://localhost:$($ports.auth)"
$env:MASTERDATA_URL = "http://localhost:$($ports.masterdata)"
$env:PROCUREMENT_URL = "http://localhost:$($ports.procurement)"
$env:AUDIT_URL = "http://localhost:$($ports.audit)"
$env:GATEWAY_PORT = $ports.gateway
$env:AUTH_PORT = $ports.auth
$env:MASTERDATA_PORT = $ports.masterdata
$env:PROCUREMENT_PORT = $ports.procurement
$env:AUDIT_PORT = $ports.audit

Write-Host "Building the shared library and services..."
& $mvn -q -f "$root\backend\pom.xml" install -DskipTests
if ($LASTEXITCODE -ne 0) { Write-Error "Build failed"; exit 1 }

if (-not (Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue | Where-Object { $_.RemotePort -eq 0 })) {
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle='SUTRA document service'; & '$root\ai\run.ps1'"
}

# Audit first (the others deliver audit events to it), the gateway last.
foreach ($service in @("audit-service", "auth-service", "masterdata-service", "procurement-service", "gateway")) {
    $title = "$label $service"
    Start-Process powershell -ArgumentList "-NoExit", "-Command",
        "`$host.UI.RawUI.WindowTitle='$title'; Set-Location '$root\backend\$service'; & '$mvn' -q spring-boot:run -DskipTests"
}

if (-not $Acceptance) {
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle='SUTRA frontend'; Set-Location '$root\frontend'; npm run dev"
    Write-Host "Starting... open http://localhost:5173 in about a minute (admin / Admin@123 on a fresh database)."
} else {
    Write-Host "Starting the acceptance copy on http://localhost:9080 ... then run:  ai\.venv\Scripts\python ai\scripts\acceptance_test.py"
}
