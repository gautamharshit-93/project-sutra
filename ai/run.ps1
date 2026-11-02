# Starts the SUTRA document service on http://localhost:8000
# First time:  python -m venv .venv ; .\.venv\Scripts\python -m pip install -r requirements-dev.txt
Set-Location $PSScriptRoot
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
