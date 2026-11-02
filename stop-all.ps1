# Stops the SUTRA services listening on their usual ports (only Java, Python or Node processes started from this
# project). Usage:  .\stop-all.ps1            all services
#                   .\stop-all.ps1 8083       just one port
param([int[]]$Ports = @(8080, 8081, 8082, 8083, 8084, 8000, 5173))

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
