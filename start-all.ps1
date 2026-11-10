# Starts Project SUTRA: the Python document reader, the gateway, the ten Java services and the frontend, each in its
# own window.
#
#   .\start-all.ps1                 the real system on sutra_db: gateway http://localhost:8080, UI http://localhost:5173
#   .\start-all.ps1 -Acceptance     a separate copy for ai\scripts\acceptance_test.py: sutra_test database, gateway on
#                                   :9080 (services 9081-9090), no frontend. Real data is never touched.
#
# Groq reads quotations (Tesseract OCR for scanned PDFs) when the GROQ_API_KEY user environment variable is set
# (see README); without it the rule-based reader is used. Stop everything with  .\stop-all.ps1
param([switch]$Acceptance)

$root = $PSScriptRoot
$mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) { "mvn" } else { "C:\tools\apache-maven-3.9.11\bin\mvn.cmd" }

# port offset: the acceptance copy runs every service 1000 ports higher
$base = if ($Acceptance) { 9000 } else { 8000 }
$ports = [ordered]@{
    gateway = $base + 80; auth = $base + 81; masterdata = $base + 82; rfq = $base + 83; audit = $base + 84
    quotation = $base + 85; document = $base + 86; extraction = $base + 87; comparison = $base + 88
    approval = $base + 89; reporting = $base + 90
}
if ($Acceptance) {
    $env:DB_URL = "jdbc:postgresql://localhost:5432/sutra_test"
    $label = "SUTRA acceptance"
    # The acceptance scripts sign in dozens of test users from this one computer within a minute.
    $env:SUTRA_PUBLIC_RATE_LIMIT = "1000"
} else {
    $env:DB_URL = "jdbc:postgresql://localhost:5432/sutra_db"
    $label = "SUTRA"
    $env:SUTRA_PUBLIC_RATE_LIMIT = "20"   # sign-in and vendor sign-up: per minute, per IP address
}

# Every service gets every other service's address and its own port (AUTH_URL/AUTH_PORT, RFQ_URL/RFQ_PORT, ...).
foreach ($name in $ports.Keys) {
    $upper = $name.ToUpper()
    Set-Item "env:${upper}_PORT" $ports[$name]
    Set-Item "env:${upper}_URL" "http://localhost:$($ports[$name])"
}

Write-Host "Building the shared libraries and services..."
& $mvn -q -f "$root\backend\pom.xml" install -DskipTests
if ($LASTEXITCODE -ne 0) { Write-Error "Build failed"; exit 1 }

if (-not (Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue | Where-Object { $_.RemotePort -eq 0 })) {
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle='SUTRA document reader'; & '$root\ai\run.ps1'"
}

# Sign-in tokens are signed with JWT_SECRET. The first run creates a random one and keeps it as a Windows user
# setting, so the built-in development secret is never used on this machine.
if (-not $env:JWT_SECRET) { $env:JWT_SECRET = [Environment]::GetEnvironmentVariable("JWT_SECRET", "User") }
if (-not $env:JWT_SECRET) {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $env:JWT_SECRET = [Convert]::ToBase64String($bytes)
    [Environment]::SetEnvironmentVariable("JWT_SECRET", $env:JWT_SECRET, "User")
    Write-Host "Created a random JWT_SECRET (Windows user setting)."
}

# Each service runs its built jar in a small JVM (one process per service, not one Maven plus one app).
function Start-Service([string]$module) {
    $jar = Get-ChildItem "$root\backend\$module\target\$module-*.jar" | Where-Object { $_.Name -notlike '*-plain.jar' } |
        Select-Object -First 1
    $title = "$label $module"
    Start-Process powershell -ArgumentList "-NoExit", "-Command",
        "`$host.UI.RawUI.WindowTitle='$title'; Set-Location '$root\backend\$module'; java -Xmx256m -XX:+UseSerialGC -jar '$($jar.FullName)'"
}

# Waits until a service answers its health check (its database migrations have then run).
function Wait-Healthy([string]$name, [int]$port) {
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        try { Invoke-WebRequest "http://localhost:$port/actuator/health" -UseBasicParsing -TimeoutSec 3 | Out-Null; return }
        catch { Start-Sleep 2 }
    }
    Write-Warning "$name did not report healthy on port $port; starting the others anyway."
}

# Order matters for the cross-schema foreign keys (and, on a database from the single procurement service, for
# moving its tables): master data, then auth (users point to vendors), document (files point to users), RFQ (points
# to entities and users), quotation (points to RFQs, vendors and files), comparison, then approval.
# Audit, extraction, reporting and the gateway own no referenced tables and start at once.
Start-Service "audit-service"
Start-Service "extraction-service"
Start-Service "reporting-service"
Start-Service "gateway"
foreach ($step in @(@("masterdata-service", "masterdata"), @("auth-service", "auth"), @("document-service", "document"),
                     @("rfq-service", "rfq"), @("quotation-service", "quotation"), @("comparison-service", "comparison"),
                     @("approval-service", "approval"))) {
    Start-Service $step[0]
    Wait-Healthy $step[0] $ports[$step[1]]
}

if (-not $Acceptance) {
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle='SUTRA frontend'; Set-Location '$root\frontend'; npm run dev"
    Write-Host "Starting... open http://localhost:5173 (admin / Admin@123 on a fresh database)."
} else {
    Write-Host "Starting the acceptance copy on http://localhost:9080 ... then run:  ai\.venv\Scripts\python ai\scripts\acceptance_test.py"
}
