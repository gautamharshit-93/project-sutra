"""Fuzzy matching of quoted lines to requested lines with rapidfuzz."""
import re

from rapidfuzz import fuzz, utils

from .schemas import MatchQuotedItem, MatchRequestItem, MatchScore

_UNIT_ALIASES = {"nos": "unit", "no": "unit", "pcs": "unit", "pc": "unit", "piece": "unit", "pieces": "unit",
                 "ea": "unit", "each": "unit", "unit": "unit", "units": "unit", "number": "unit"}


def _normalise_unit(unit: str | None) -> str | None:
    if not unit:
        return None
    key = re.sub(r"[^a-z]", "", unit.lower())
    return _UNIT_ALIASES.get(key, key)


def score_pair(quoted: MatchQuotedItem, requested: MatchRequestItem) -> float:
    """0..1 similarity. token_set_ratio tolerates extra words (brand, model) on either side."""
    q = utils.default_process(quoted.description)
    r = utils.default_process(requested.description)
    if not q or not r:
        return 0.0
    base = max(fuzz.token_set_ratio(q, r), 0.9 * fuzz.token_sort_ratio(q, r))
    if requested.specifications:
        with_specs = utils.default_process(requested.description + " " + requested.specifications)
        base = max(base, 0.95 * fuzz.token_set_ratio(q, with_specs))
    score = base / 100.0
    # Small boosts for agreeing unit and quantity; never enough on their own to create a match.
    if _normalise_unit(quoted.unitOfMeasure) and _normalise_unit(quoted.unitOfMeasure) == _normalise_unit(requested.unitOfMeasure):
        score += 0.03
    if quoted.quantity is not None and requested.quantity is not None and quoted.quantity == requested.quantity:
        score += 0.03
    return round(min(score, 1.0), 3)


def score_all(requested: list[MatchRequestItem], quoted: list[MatchQuotedItem]) -> list[MatchScore]:
    return [MatchScore(quotedIndex=q.index, requestItemId=r.id, score=score_pair(q, r))
            for q in quoted for r in requested]
