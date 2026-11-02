# Applies database/migrations/V*.sql in order with psql, without starting the backend.
# Normally unnecessary: the backend runs the same scripts through Flyway on startup.
# Usage: .\02_apply_migrations.ps1 [-Database sutra_db]
param(
    [string]$Database = "sutra_db",
    [string]$DbHost = "localhost",
    [string]$User = "sutra",
    [string]$Password = "sutra_pass",
    [string]$Psql = "C:\Program Files\PostgreSQL\18\bin\psql.exe"
)
$ErrorActionPreference = "Stop"
$env:PGPASSWORD = $Password
$migrations = Get-ChildItem (Join-Path $PSScriptRoot "..\migrations") -Filter "V*__*.sql" |
    Sort-Object { [int]($_.Name -replace '^V(\d+)__.*$', '$1') }
foreach ($m in $migrations) {
    Write-Host "Applying $($m.Name)"
    & $Psql -U $User -h $DbHost -d $Database -v ON_ERROR_STOP=1 -q -f $m.FullName
    if ($LASTEXITCODE -ne 0) { throw "Migration $($m.Name) failed" }
}
