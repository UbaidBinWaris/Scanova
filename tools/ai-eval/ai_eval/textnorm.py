"""Text normalisation shared by the metrics, for English and Urdu.

Urdu text arrives with many equivalent encodings: Arabic and Urdu letter
variants (yeh/kaf/heh), optional diacritics, tatweel, bidi controls and three
digit sets (ASCII, Arabic-Indic, Extended Arabic-Indic). Metrics compare
meaning, not encoding, so both sides pass through `normalise` first.
Code points are written as numbers on purpose: no invisible characters here.
"""

import re
import unicodedata

# Arabic-script letters that Urdu writes with a different code point.
_LETTERS = {
    0x064A: 0x06CC,  # ARABIC YEH -> FARSI YEH (Urdu choti ye)
    0x0649: 0x06CC,  # ALEF MAKSURA -> FARSI YEH
    0x0643: 0x06A9,  # ARABIC KAF -> KEHEH
    0x0647: 0x06C1,  # HEH -> HEH GOAL
    0x06D5: 0x06C1,  # AE -> HEH GOAL
    0x0629: 0x06C3,  # TEH MARBUTA -> TEH MARBUTA GOAL
}

# Invisible characters that change nothing a reader sees.
_DROP = [
    0x0640,  # TATWEEL
    0x200C, 0x200D,  # ZWNJ, ZWJ
    0x200E, 0x200F, 0x061C,  # LRM, RLM, ARABIC LETTER MARK
    0x202A, 0x202B, 0x202C, 0x202D, 0x202E,  # bidi embeddings and overrides
    0x2066, 0x2067, 0x2068, 0x2069,  # bidi isolates
    0xFEFF,  # BOM
]  # fmt: skip

_PUNCT = {
    0x060C: ",",  # ARABIC COMMA
    0x061B: ";",  # ARABIC SEMICOLON
    0x061F: "?",  # ARABIC QUESTION MARK
    0x06D4: ".",  # ARABIC FULL STOP (Urdu)
    0x066B: ".",  # ARABIC DECIMAL SEPARATOR
    0x066C: ",",  # ARABIC THOUSANDS SEPARATOR
    0x2018: "'", 0x2019: "'", 0x201C: '"', 0x201D: '"',
    0x2013: "-", 0x2014: "-", 0x2212: "-",
}  # fmt: skip

_DIGIT_TABLE = {0x0660 + i: str(i) for i in range(10)} | {0x06F0 + i: str(i) for i in range(10)}
_TABLE = {**{k: chr(v) for k, v in _LETTERS.items()}, **dict.fromkeys(_DROP), **_PUNCT, **_DIGIT_TABLE}

_WS = re.compile(r"\s+")

_ARABIC_RANGES = ((0x0600, 0x06FF), (0x0750, 0x077F), (0xFB50, 0xFDFF), (0xFE70, 0xFEFF))


def strip_marks(text):
    """Removes combining marks (Arabic harakat, Latin accents)."""
    decomposed = unicodedata.normalize("NFD", text)
    return unicodedata.normalize("NFC", "".join(c for c in decomposed if not unicodedata.combining(c)))


def digits_to_ascii(text):
    return text.translate(_DIGIT_TABLE)


def normalise(text, casefold=True):
    """Canonical form for comparisons: NFKC, Urdu letter forms, ASCII digits, one space."""
    if text is None:
        return ""
    text = unicodedata.normalize("NFKC", str(text)).translate(_TABLE)
    text = strip_marks(text)
    if casefold:
        text = text.casefold()
    return _WS.sub(" ", text).strip()


def _is_arabic(c):
    code = ord(c)
    return any(low <= code <= high for low, high in _ARABIC_RANGES)


def arabic_script_ratio(text):
    """Share of letters that are Arabic script (0.0 when there are no letters)."""
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return 0.0
    return sum(1 for c in letters if _is_arabic(c)) / len(letters)


def script_of(text):
    """'arabic', 'latin' or 'none', by majority of letters."""
    if not any(c.isalpha() for c in text):
        return "none"
    return "arabic" if arabic_script_ratio(text) >= 0.5 else "latin"
