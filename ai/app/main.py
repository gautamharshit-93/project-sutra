"""FastAPI app: /health, /parse, /match, /extract. Called only by the SUTRA backend.

AI extraction (Groq, with Tesseract OCR for scanned PDFs) runs in the backend. This service converts files into plain
text for those engines, fuzzy-matches quoted lines to requested lines, and is itself the rule-based extraction
engine (/extract, no AI).

File contents are never logged; only file names and sizes.
"""
import logging

from fastapi import FastAPI, File, HTTPException, UploadFile

from . import matcher, parsers, rules
from .schemas import MatchRequest, MatchResponse, ParsedDocument

MAX_BYTES = 25 * 1024 * 1024

log = logging.getLogger("sutra.ai")
app = FastAPI(title="SUTRA document service", version="1.0.0")


async def _read(file: UploadFile) -> tuple[str, bytes]:
    name = file.filename or "upload"
    content = await file.read(MAX_BYTES + 1)
    if len(content) > MAX_BYTES:
        raise HTTPException(status_code=413, detail="File exceeds 25 MB")
    log.info("received %s (%d bytes)", name, len(content))
    return name, content


def _parse(name: str, content: bytes) -> parsers.Document:
    try:
        return parsers.parse(name, content)
    except parsers.UnreadableDocumentError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.get("/health")
def health():
    return {"status": "UP"}


@app.post("/parse", response_model=ParsedDocument)
async def parse(file: UploadFile = File(...)):
    name, content = await _read(file)
    doc = _parse(name, content)
    text = doc.as_text()
    return ParsedDocument(format=doc.format, text=text, pages=doc.pages, charCount=len(text),
                          likelyScanned=any("scanned" in w for w in doc.warnings), warnings=doc.warnings)


@app.post("/extract")
async def extract(files: list[UploadFile] = File(...)):
    """Rule-based extraction of one quotation from all of its files (the backend's ExtractedQuotation shape)."""
    if not files:
        raise HTTPException(status_code=400, detail="No file was uploaded")
    contents = [await _read(f) for f in files]
    try:
        return rules.extract(contents)
    except rules.NoReadableFileError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.post("/match", response_model=MatchResponse)
def match(request: MatchRequest):
    return MatchResponse(scores=matcher.score_all(request.requestItems, request.quotedItems))
