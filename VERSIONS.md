# Project SUTRA - Version Matrix

All versions below were chosen to work together and are pinned in the build files.
Upgrade a row only together with the rows it depends on.

## Runtimes (installed on the dev machine)

| Component  | Version            | Notes |
|------------|--------------------|-------|
| JDK        | 17 (Temurin 17.0.20) | Build target is Java 17 (Spring Boot 4 minimum). JDK 21 also works. |
| Maven      | 3.9.11             | Installed at `C:\tools\apache-maven-3.9.11` |
| PostgreSQL | 18                 | Service `postgresql-x64-18`, port 5432 |
| Node.js    | 24.18 (npm 11.16)  | Vite 6 requires Node 18, 20 or >= 22 |
| Python     | 3.12.10            | AI service runs in `ai/.venv` |

## Backend (`backend/pom.xml`)

| Library | Version | Why this version |
|---------|---------|------------------|
| Spring Boot | 4.1.2 | Latest supported line (3.5.x open-source support ended 2026-06-30); manages the rows marked *BOM* |
| Spring Framework *BOM* | 7.0.10 | |
| Spring Security *BOM* | 7.1.2 | |
| Spring Data JPA *BOM* | 4.1.2 | Type-safe sorting with method references |
| Hibernate ORM *BOM* | 7.4.13.Final | |
| Jackson 3 *BOM* | 3.1.7 | Used by Spring (`tools.jackson.*`) |
| Jackson 2 *BOM* | 2.21.7 | Used only by jjwt-jackson. Jackson 2 and 3 live in different packages and coexist |
| Flyway *BOM* | 12.4.0 | Runs on PostgreSQL 18.6 without warnings |
| PostgreSQL JDBC *BOM* | 42.7.14 | |
| Lombok *BOM* | 1.18.48 | |
| JUnit Jupiter / Mockito *BOM* | 6.0.3 / 5.23.0 | |
| Groq API | REST `POST /openai/v1/chat/completions`, strict `json_schema`; models `openai/gpt-oss-120b` > `openai/gpt-oss-20b` > `qwen/qwen3.8-27b` | Spring's RestClient; no SDK dependency |
| Apache PDFBox | 3.0.8 | Renders scanned PDF pages for OCR |
| Tesseract OCR | 5.4.0 (UB-Mannheim Windows build) | Installed program, called as a process; English language data |
| jjwt | 0.12.6 | |
| springdoc-openapi | 3.2.0 | Built against Spring Boot 4.1.2 |

History: the project first used Spring Boot 3.5.16 and was migrated to 4.1.2 because 3.5.x is out of open-source support.

## Frontend (`frontend/package.json`)

| Package | Version | Compatibility |
|---------|---------|---------------|
| react / react-dom | 18.3.1 | |
| react-router-dom | 6.30.6 | React 18 |
| axios | 1.21.0 | |
| vite | 6.4.5 | Node 18, 20 or >= 22 |
| @vitejs/plugin-react | 4.7.0 | Vite 4-7 |
| vitest | 3.2.7 | Vite 5-7 |
| @testing-library/react | 16.3.3 | React 18 or 19, needs @testing-library/dom 10 |
| @testing-library/dom | 10.4.2 | Peer of @testing-library/react 16 |
| @testing-library/jest-dom | 6.9.1 | 6.10.0 was withdrawn by its authors (breaking change in a minor release) |
| @testing-library/user-event | 14.6.8 | |
| jsdom | 27.4.0 | 26.x pulled in the deprecated whatwg-encoding; 27.4 supports Node 24 |
| esbuild (via Vite) | 0.25.12 | Install script approved in package.json `allowScripts` (npm 11 blocks it otherwise) |

Exact versions are pinned in package.json; `npm install` and `npm ls` report no conflicts or deprecations.
React Router v7 behaviours (`v7_startTransition`, `v7_relativeSplatPath`) are enabled so a later upgrade is drop-in.

## AI service (`ai/requirements.txt`, Python 3.12)

| Package | Version |
|---------|---------|
| fastapi | 0.162.0 (brings a compatible pydantic 2.x / starlette) |
| uvicorn | 0.54.0 |
| python-multipart | 0.0.33 |
| pdfplumber | 0.11.10 |
| openpyxl | 3.1.5 |
| xlrd | 2.0.2 (legacy .xls) |
| python-docx | 1.2.0 |
| rapidfuzz | 3.14.6 |
| pytest / httpx2 | 9.2.0 / 2.16.0 (tests; Starlette 1.x's TestClient uses httpx2) |

Resolved by pip with no conflicts (`pip check` clean): pydantic 2.14.2, starlette 1.10.0, pdfminer.six 20260107.
| reportlab | 5.1.0 (tests only: builds sample PDFs) |
