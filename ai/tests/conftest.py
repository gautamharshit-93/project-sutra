import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from samples import generate_samples  # noqa: E402


@pytest.fixture(scope="session")
def xlsx_bytes() -> bytes:
    return generate_samples.build_xlsx()


@pytest.fixture(scope="session")
def docx_bytes() -> bytes:
    return generate_samples.build_docx()


@pytest.fixture(scope="session")
def pdf_bytes() -> bytes:
    return generate_samples.build_pdf()
