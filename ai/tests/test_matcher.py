from decimal import Decimal

from app.matcher import score_all, score_pair
from app.schemas import MatchQuotedItem, MatchRequestItem

REQUESTED = [
    MatchRequestItem(id=1, description="Laptop - Dell Latitude 5440", specifications="i5, 16GB RAM, 512GB SSD",
                     unitOfMeasure="Nos", quantity=Decimal(10)),
    MatchRequestItem(id=2, description="24-inch LED Monitor, Full HD", unitOfMeasure="Nos", quantity=Decimal(10)),
    MatchRequestItem(id=3, description="Wireless Keyboard and Mouse Combo", unitOfMeasure="Nos", quantity=Decimal(10)),
]


def best_match(description: str) -> int:
    quoted = [MatchQuotedItem(index=0, description=description, unitOfMeasure="Nos", quantity=Decimal(10))]
    scores = score_all(REQUESTED, quoted)
    return max(scores, key=lambda s: s.score).requestItemId


def test_vendor_wording_matches_the_right_request_line():
    assert best_match("Dell Latitude 5440 Laptop i5 16GB 512GB SSD") == 1
    assert best_match("Samsung 24 inch FHD Monitor LS24C310") == 2
    assert best_match("Logitech MK270 Wireless Keyboard Mouse Combo") == 3


def test_good_match_scores_high_and_unrelated_scores_low():
    laptop = MatchQuotedItem(index=0, description="Laptop Dell Latitude 5440 (Core i5, 16 GB RAM)")
    chair = MatchQuotedItem(index=1, description="Ergonomic office chair with armrest")
    assert score_pair(laptop, REQUESTED[0]) >= 0.8
    assert score_pair(chair, REQUESTED[0]) < 0.35


def test_scores_cover_every_pair_and_stay_in_range():
    quoted = [MatchQuotedItem(index=i, description=d) for i, d in enumerate(["monitor", "keyboard"])]
    scores = score_all(REQUESTED, quoted)
    assert len(scores) == 6
    assert all(0.0 <= s.score <= 1.0 for s in scores)
