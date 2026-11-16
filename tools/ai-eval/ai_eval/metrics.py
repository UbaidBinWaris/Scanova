"""Metrics of §11.6: character error rate, retrieval ranks, field matching and
faithfulness checks. Pure functions, stdlib only."""

import re
from decimal import Decimal, InvalidOperation

from .textnorm import digits_to_ascii, normalise

# ── OCR ──────────────────────────────────────────────────────────────────────


def levenshtein(a, b):
    """Edit distance (insertions, deletions, substitutions), O(len(a)·len(b)) time, O(len(b)) memory."""
    if a == b:
        return 0
    if len(a) < len(b):
        a, b = b, a
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def cer(reference, hypothesis):
    """Character error rate after normalisation. Can exceed 1.0 when the output is mostly insertions."""
    ref = normalise(reference, casefold=False)
    hyp = normalise(hypothesis, casefold=False)
    if not ref:
        return 0.0 if not hyp else 1.0
    return levenshtein(ref, hyp) / len(ref)


# ── Retrieval ────────────────────────────────────────────────────────────────


def first_rank(ranking, relevant):
    """1-based rank of the first relevant id in `ranking`, or None."""
    for position, doc_id in enumerate(ranking, 1):
        if doc_id in relevant:
            return position
    return None


def recall_at_k(ranking, relevant, k):
    if not relevant:
        return 0.0
    return len(set(ranking[:k]) & set(relevant)) / len(relevant)


def reciprocal_rank(ranking, relevant):
    rank = first_rank(ranking, relevant)
    return 0.0 if rank is None else 1.0 / rank


# ── Field normalisation ──────────────────────────────────────────────────────

MONTHS = {
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6,
    "july": 7, "august": 8, "september": 9, "october": 10, "november": 11, "december": 12,
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "jun": 6, "jul": 7, "aug": 8,
    "sep": 9, "sept": 9, "oct": 10, "nov": 11, "dec": 12,
}  # fmt: skip

# Urdu month names, normalised the same way as the text they are matched in.
URDU_MONTHS = {
    normalise(name): number
    for number, name in enumerate(
        ["جنوری", "فروری", "مارچ", "اپریل", "مئی", "جون", "جولائی", "اگست", "ستمبر", "اکتوبر", "نومبر", "دسمبر"],
        1,
    )
}

_MONTH_WORD = "|".join(sorted((re.escape(m) for m in [*MONTHS, *URDU_MONTHS]), key=len, reverse=True))
_DATE_PATTERNS = [
    (re.compile(r"(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)"), "ymd"),
    (re.compile(r"(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})(?!\d)"), "dmy"),
    (re.compile(rf"(?<!\d)(\d{{1,2}})(?:st|nd|rd|th)?\s+({_MONTH_WORD})\.?,?\s+(\d{{4}})(?!\d)"), "d_month_y"),
    (re.compile(rf"\b({_MONTH_WORD})\.?\s+(\d{{1,2}})(?:st|nd|rd|th)?,?\s+(\d{{4}})(?!\d)"), "month_d_y"),
]


def _iso(year, month, day):
    try:
        year, month, day = int(year), int(month), int(day)
    except (TypeError, ValueError):
        return None
    if not (1900 <= year <= 2100 and 1 <= month <= 12 and 1 <= day <= 31):
        return None
    return f"{year:04d}-{month:02d}-{day:02d}"


def _month(word):
    word = word.rstrip(".")
    return MONTHS.get(word) or URDU_MONTHS.get(word)


def find_dates(text):
    """Every date written in `text`, as ISO strings, in order of appearance. Day-first for d/m/y."""
    if not text:
        return []
    text = normalise(text)
    found = []
    for pattern, kind in _DATE_PATTERNS:
        for match in pattern.finditer(text):
            a, b, c = match.groups()
            if kind == "ymd":
                iso = _iso(a, b, c)
            elif kind == "dmy":
                iso = _iso(c, b, a)
            elif kind == "d_month_y":
                iso = _iso(c, _month(b), a)
            else:
                iso = _iso(c, _month(a), b)
            if iso is not None:
                found.append((match.start(), iso))
    return [iso for _, iso in sorted(found)]


def normalise_date(value):
    """An extracted date as ISO `YYYY-MM-DD`, or None when it cannot be read."""
    if value is None:
        return None
    dates = find_dates(str(value))
    return dates[0] if dates else None


_CURRENCIES = {
    "pkr": "PKR", "rs": "PKR", "rs.": "PKR", "rupees": "PKR", "rupee": "PKR",
    normalise("روپے"): "PKR", normalise("روپیہ"): "PKR", normalise("روپئے"): "PKR",
    "usd": "USD", "$": "USD", "us$": "USD", "dollars": "USD",
    "gbp": "GBP", "£": "GBP", "pounds": "GBP",
    "eur": "EUR", "€": "EUR", "euros": "EUR",
    "aed": "AED", "sar": "SAR",
}  # fmt: skip


def normalise_currency(value):
    if value is None:
        return None
    key = normalise(value).strip()
    if key.upper() in {"PKR", "USD", "GBP", "EUR", "AED", "SAR"}:
        return key.upper()
    return _CURRENCIES.get(key)


_AMOUNT = re.compile(r"\d[\d,]*(?:\.\d+)?")


def normalise_amount(value):
    """An amount as a plain decimal with two places (`12500.00`), ignoring currency words and `/-`."""
    if value is None:
        return None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        number = Decimal(str(value))
    else:
        text = normalise(value).replace("/-", "")
        match = _AMOUNT.search(text)
        if match is None:
            return None
        try:
            number = Decimal(match.group(0).replace(",", ""))
        except InvalidOperation:
            return None
    return f"{number.quantize(Decimal('0.01'))}"


def normalise_identifier(value):
    """Document numbers: letters and digits only, upper case (`INV-2026/0042` == `inv 2026 0042`)."""
    if value is None:
        return None
    text = re.sub(r"[\W_]+", "", normalise(value))
    return text.upper() or None


def normalise_party(value):
    if value is None:
        return None
    text = re.sub(r"[^\w\s]", " ", normalise(value))
    text = re.sub(r"\s+", " ", text).strip()
    return text or None


def field_matches(name, expected, actual, aliases=()):
    """Exact match after the field's normalisation. `expected is None` means "must be absent"."""
    normalisers = {
        "date": normalise_date,
        "amount": normalise_amount,
        "currency": normalise_currency,
        "number": normalise_identifier,
        "party": normalise_party,
    }
    fn = normalisers[name]
    got = fn(actual)
    if expected is None:
        return got is None
    return got is not None and got in {fn(expected), *(fn(alias) for alias in aliases)}


# ── Faithfulness ─────────────────────────────────────────────────────────────

_NUMBER = re.compile(r"\d+(?:[.,]\d+)*")


def numbers_in(text, parts=False):
    """Canonical numbers written in `text`: thousands separators, leading zeros and `.00` dropped.

    With `parts`, the pieces between separators count too (`15.03.2026` → 15, 3, 2026), which is
    how the source side is read: generous, so only numbers really absent from it count as invented.
    """
    found = set()
    for match in _NUMBER.finditer(digits_to_ascii(normalise(text or ""))):
        raw = match.group(0)
        whole = raw.replace(",", "")
        if whole.count(".") <= 1:
            found.add(_canonical_number(whole))
        if parts or whole.count(".") > 1:
            found.update(_canonical_number(part) for part in re.split(r"[.,]", raw))
    found.discard("")
    return found


def _canonical_number(raw):
    if "." in raw:
        head, tail = raw.split(".", 1)
        tail = tail.rstrip("0")
        head = head.lstrip("0") or "0"
        return head if not tail else f"{head}.{tail}"
    return raw.lstrip("0") or "0"


def invented_facts(summary, source_text, known_dates=()):
    """Numbers and dates in a summary that the source does not contain. Empty means faithful."""
    source_numbers = numbers_in(source_text, parts=True)
    source_dates = set(find_dates(source_text)) | set(known_dates)
    invented = sorted(f"number:{n}" for n in numbers_in(summary) - source_numbers)
    invented += sorted(f"date:{d}" for d in set(find_dates(summary)) - source_dates)
    return invented
