"""API models. Field names are camelCase to match the Java records in com.sutra.ai."""
from decimal import Decimal

from pydantic import BaseModel, Field


class ParsedDocument(BaseModel):
    format: str
    text: str
    pages: int
    charCount: int
    likelyScanned: bool
    warnings: list[str] = Field(default_factory=list)


class MatchRequestItem(BaseModel):
    id: int
    description: str
    specifications: str | None = None
    unitOfMeasure: str | None = None
    quantity: Decimal | None = None


class MatchQuotedItem(BaseModel):
    index: int
    description: str
    unitOfMeasure: str | None = None
    quantity: Decimal | None = None


class MatchRequest(BaseModel):
    requestItems: list[MatchRequestItem]
    quotedItems: list[MatchQuotedItem]


class MatchScore(BaseModel):
    quotedIndex: int
    requestItemId: int
    score: float


class MatchResponse(BaseModel):
    scores: list[MatchScore]
