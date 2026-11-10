# Stops the SUTRA processes listening on their usual ports: gateway 8080, services 8081-8090, document reader 8000,
# UI 5173 (only Java, Python or Node processes started from this project). Usage:
#   .\stop-all.ps1                       everything
#   .\stop-all.ps1 8083                  one port
#   .\stop-all.ps1 (9080..9090)          the acceptance copy
param([int[]]$Ports = @(8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 8089, 8090, 8000, 5173))

foreach ($port in $Ports) {
    # The State column is not always filled in, so take the listening socket's owner (closed sockets show pid 0)
    $owners = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -ne 0 -and $_.RemotePort -eq 0 } |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($id in $owners) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$id"
        if ($null -eq $proc) { continue }
        $cmd = [string]$proc.CommandLine
        if ($cmd -match 'com\.sutra|uvicorn app\.main|vite|PROJECT SUTRA|spring-boot|(-service|gateway)-\d+\.\d+\.\d+\.jar') {
            Stop-Process -Id $id -Force
            Write-Host "Stopped $($proc.Name) (pid $id) on port $port"
        } else {
            Write-Host "Port $port is used by $($proc.Name) (pid $id), which is not a SUTRA service - left running"
        }
    }
}
