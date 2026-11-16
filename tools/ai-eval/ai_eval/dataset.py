"""Deterministic synthetic golden dataset (§11.6): documents with ground truth,
search queries and questions. Pure Python; `render.py` draws the page images.

Layout on disk (written by `aieval.py generate`):

    manifest.json          generator version, seed, fonts, counts
    docs.jsonl             one document per line (text, fields, user, ...)
    queries.json           semantic-search queries with relevant document ids
    questions.json         Q&A questions, own and other user's
    images/<id>.png        clean page (150 dpi A4), when rendered
    images/<id>-photo.jpg  "poor photo" of the same page, for some documents
"""

import json
import random
import re
import textwrap

from . import content as C

GENERATOR_VERSION = 1
DEFAULT_SEED = 20261116

USERS = ("alice", "bilal")


def _to_urdu_digits(text):
    return text.translate(str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹"))


class _Dates:
    """Dates with an unambiguous day (13–28) so d/m/y and m/d/y cannot be confused."""

    def __init__(self, rng):
        self.rng = rng

    def pick(self, year_from=2025, year_to=2026):
        year = self.rng.randint(year_from, year_to)
        month = self.rng.randint(1, 10 if year == 2026 else 12)
        day = self.rng.randint(13, 28)
        return year, month, day

    def en(self, ymd):
        y, m, d = ymd
        style = self.rng.randrange(4)
        if style == 0:
            return f"{d} {C.EN_MONTHS[m - 1]} {y}"
        if style == 1:
            return f"{d:02d}/{m:02d}/{y}"
        if style == 2:
            return f"{y}-{m:02d}-{d:02d}"
        return f"{C.EN_MONTHS[m - 1]} {d}, {y}"

    def ur(self, ymd):
        y, m, d = ymd
        style = self.rng.randrange(4)
        if style == 0:
            return f"{d} {C.URDU_MONTHS[m - 1]} {y}"
        if style == 1:
            return f"{d:02d}-{m:02d}-{y}"
        if style == 2:
            return _to_urdu_digits(f"{d:02d}-{m:02d}-{y}")
        return f"{d:02d}/{m:02d}/{y}"


def _iso(ymd):
    y, m, d = ymd
    return f"{y:04d}-{m:02d}-{d:02d}"


def _money(value, currency, lang):
    """Amount as printed: `PKR 12,500` / `USD 44.64` / `12,500 روپے`."""
    if currency == "PKR":
        printed = f"{value:,.0f}" if value == int(value) else f"{value:,.2f}"
    else:
        printed = f"{value:,.2f}"
    if lang == "ur":
        return f"{printed} روپے"
    return f"{currency} {printed}"


# Numbers joined by "-" (dates, phone and document numbers) would be shown in
# reverse segment order inside Urdu text by the Unicode bidi algorithm (rule W2
# turns them into Arabic numbers, which "-" does not join). Urdu word processors
# keep them left-to-right; a left-to-right isolate does the same here. The
# isolates are invisible and removed by textnorm.normalise.
_JOINED_NUMBER = re.compile(r"[0-9\u06f0-\u06f9]+(?:-[0-9\u06f0-\u06f9]+)+")


def _isolate_numbers(line):
    return _JOINED_NUMBER.sub(lambda m: f"\u2066{m.group(0)}\u2069", line)


def _wrap(text, width):
    return textwrap.wrap(text, width=width, break_long_words=False, break_on_hyphens=False)


class Builder:
    def __init__(self, seed=DEFAULT_SEED):
        self.rng = random.Random(seed)
        self.dates = _Dates(self.rng)
        self.docs = []
        self.queries = []
        self.questions = []

    # ── shared pieces ────────────────────────────────────────────────────────

    def _currency(self, lang):
        if lang == "ur":
            return "PKR"
        return self.rng.choice(["PKR", "PKR", "USD", "GBP", "EUR"])

    def _price(self, pkr, currency):
        rate = {"PKR": 1, "USD": 280, "GBP": 355, "EUR": 305}[currency]
        return round(pkr / rate, 2) if rate != 1 else float(pkr)

    def _phone(self):
        return f"0{self.rng.randint(300, 349)}-{self.rng.randint(1000000, 9999999)}"

    def _address(self, lang, index):
        house = self.rng.randint(3, 240)
        if lang == "ur":
            return f"مکان نمبر {house}، {C.STREETS_UR[index % 6]}، {C.CITIES_UR[index % 6]}"
        return f"House {house}, {C.STREETS_EN[index % 6]}, {C.CITIES_EN[index % 6]}"

    def _style(self, lang, kind, index):
        if lang == "ur":
            # Alternate Naskh and Nastaliq; two Nastaliq faces.
            return ("naskh", "nastaliq", "naskh", "nastaliq-gulzar")[index % 4]
        if kind == "receipt":
            return "mono"
        return ("sans", "serif")[index % 2]

    def _add(self, doc):
        self.docs.append(doc)
        return doc

    # ── documents ────────────────────────────────────────────────────────────

    def invoice(self, topic, lang, index, user):
        t = topic[lang]
        currency = self._currency(lang)
        ymd = self.dates.pick()
        number = f"{ymd[0]}-{self.rng.randint(100, 9999):04d}"
        customer = C.PEOPLE_EN[index % 12] if lang == "en" else C.PEOPLE_UR[index % 12][0]
        items, total = [], 0.0
        for name, pkr in t["items"]:
            price = self._price(pkr, currency)
            total += price
            items.append((name, price))
        tax = round(total * 0.05, 2)
        total = round(total + tax, 2)
        if lang == "en":
            lines = [t["party"], self._address("en", index), f"Phone: {self._phone()}", "", "INVOICE",
                     f"Invoice No: INV-{number}", f"Date: {self.dates.en(ymd)}", f"Bill to: {customer}", ""]
            lines += [f"{name}  ...  {_money(price, currency, 'en')}" for name, price in items]
            lines += ["", f"Sales tax 5%: {_money(tax, currency, 'en')}", f"Total due: {_money(total, currency, 'en')}", "",
                      "Payment is due within 30 days. Thank you for your business."]
        else:
            lines = [t["party"], self._address("ur", index), f"فون: {self._phone()}", "", "بل / انوائس",
                     f"انوائس نمبر: {number}", f"تاریخ: {self.dates.ur(ymd)}", f"بنام: {customer}", ""]
            lines += [f"{name}  ...  {_money(price, currency, 'ur')}" for name, price in items]
            lines += ["", f"سیلز ٹیکس 5 فیصد: {_money(tax, currency, 'ur')}", f"کل واجب الادا رقم: {_money(total, currency, 'ur')}", "",
                      "ادائیگی 30 دن کے اندر کریں۔ آپ کے تعاون کا شکریہ۔"]
        return self._doc("invoice", topic["key"], lang, index, user, lines, date=_iso(ymd), amount=total,
                         currency=currency, number=f"INV-{number}" if lang == "en" else number, party=t["party"],
                         alias=t.get("alias"), keywords=t["keywords"], query=t["query"])

    def receipt(self, topic, lang, index, user):
        t = topic[lang]
        currency = self._currency(lang)
        ymd = self.dates.pick()
        number = str(self.rng.randint(10000, 99999))
        hour, minute = self.rng.randint(9, 21), self.rng.randint(0, 59)
        items, total = [], 0.0
        for name, pkr in t["items"]:
            price = self._price(pkr, currency)
            total += price
            items.append((name, price))
        total = round(total, 2)
        paid_by = self.rng.choice(["cash", "card"])
        if lang == "en":
            lines = [t["party"], C.CITIES_EN[index % 6], "", "CASH RECEIPT", f"Receipt No: {number}",
                     f"Date: {self.dates.en(ymd)}   Time: {hour:02d}:{minute:02d}", ""]
            lines += [f"{name}  {_money(price, currency, 'en')}" for name, price in items]
            lines += ["", f"TOTAL: {_money(total, currency, 'en')}", f"Paid by {paid_by}", "Thank you, please visit again."]
        else:
            paid = "نقد" if paid_by == "cash" else "کارڈ"
            lines = [t["party"], C.CITIES_UR[index % 6], "", "رسید", f"رسید نمبر: {number}",
                     f"تاریخ: {self.dates.ur(ymd)}   وقت: {hour:02d}:{minute:02d}", ""]
            lines += [f"{name}  {_money(price, currency, 'ur')}" for name, price in items]
            lines += ["", f"کل رقم: {_money(total, currency, 'ur')}", f"ادائیگی: {paid}", "شکریہ، دوبارہ تشریف لائیں۔"]
        return self._doc("receipt", topic["key"], lang, index, user, lines, date=_iso(ymd), amount=total,
                         currency=currency, number=number, party=t["party"], alias=t.get("alias"),
                         keywords=t["keywords"], query=t["query"])

    def letter(self, topic, lang, index, user):
        t = topic[lang]
        ymd = self.dates.pick()
        later = self.dates.pick(year_from=ymd[0], year_to=ymd[0])
        ref = f"{self.rng.randint(100, 999)}/{ymd[0]}"
        if topic["sender"] == "person":
            if lang == "en":
                party, alias, recipient = C.PEOPLE_EN[(index + 5) % 12], None, t["recipient"]
            else:
                party, alias = C.PEOPLE_UR[(index + 5) % 12]
                recipient = t["recipient"]
        else:
            party, alias = t["party"], t.get("alias")
            recipient = C.PEOPLE_EN[index % 12] if lang == "en" else C.PEOPLE_UR[index % 12][0]
        if lang == "en":
            body = t["body"].format(date2=self.dates.en(later))
            lines = [party, self._address("en", index), "", f"Ref: {ref}", f"Date: {self.dates.en(ymd)}", "",
                     f"To: {recipient}", f"Subject: {t['subject']}", "", "Dear Sir or Madam,"]
            lines += _wrap(body, 62)
            lines += ["", "Yours sincerely,", party]
        else:
            body = t["body"].format(date2=self.dates.ur(later))
            lines = [party, self._address("ur", index), "", f"حوالہ نمبر: {ref}", f"تاریخ: {self.dates.ur(ymd)}", "",
                     f"بخدمت: {recipient}", f"موضوع: {t['subject']}", "", "جناب عالی،"]
            lines += _wrap(body, 52)
            lines += ["", "آپ کا مخلص،", party]
        return self._doc("letter", topic["key"], lang, index, user, lines, date=_iso(ymd), amount=None,
                         currency=None, number=ref, party=party, alias=alias, keywords=t["keywords"],
                         query=t["query"], other_dates=[_iso(later)])

    def form(self, topic, lang, index, user):
        t = topic[lang]
        ymd = self.dates.pick()
        birth = (self.rng.randint(1975, 2012), self.rng.randint(1, 12), self.rng.randint(13, 28))
        number = f"{ymd[0]}-{self.rng.randint(10000, 99999)}"
        currency = self._currency(lang) if topic["fee"] else None
        fee = self._price(topic["fee"], currency) if topic["fee"] else None
        if lang == "en":
            name, father = C.PEOPLE_EN[(index + 2) % 12], C.FATHERS_EN[index % 6]
            lines = [t["party"], t["title"].upper(), "", f"Application No: {number}", f"Name: {name}",
                     f"Father's name: {father}", f"Date of birth: {self.dates.en(birth)}",
                     f"Address: {self._address('en', index)}", f"Phone: {self._phone()}"]
            if fee is not None:
                lines.append(f"{t['fee_label']}: {_money(fee, currency, 'en')}")
            lines += [f"Date: {self.dates.en(ymd)}", "", "Signature of applicant: ____________"]
        else:
            name, father = C.PEOPLE_UR[(index + 2) % 12][0], C.FATHERS_UR[index % 6]
            lines = [t["party"], t["title"], "", f"درخواست نمبر: {number}", f"نام: {name}", f"ولدیت: {father}",
                     f"تاریخ پیدائش: {self.dates.ur(birth)}", f"پتہ: {self._address('ur', index)}",
                     f"فون: {self._phone()}"]
            if fee is not None:
                lines.append(f"{t['fee_label']}: {_money(fee, currency, 'ur')}")
            lines += [f"تاریخ: {self.dates.ur(ymd)}", "", "دستخط درخواست گزار: ____________"]
        return self._doc("form", topic["key"], lang, index, user, lines, date=_iso(ymd), amount=fee,
                         currency=currency, number=number, party=t["party"], alias=t.get("alias"),
                         keywords=t["keywords"], query=t["query"], other_dates=[_iso(birth)])

    def id_card(self, lang, index, user):
        issue = self.dates.pick(2024, 2026)
        expiry = (issue[0] + 10, issue[1], issue[2])
        birth = (self.rng.randint(1960, 2005), self.rng.randint(1, 12), self.rng.randint(13, 28))
        # First digit 9: shaped like a national identity number, never a real one.
        number = f"9{self.rng.randint(1000, 9999)}-{self.rng.randint(1000000, 9999999)}-{self.rng.randint(1, 9)}"
        female = index % 2 == 1
        if lang == "en":
            name, alias = C.PEOPLE_EN[(index * 2 + 1) % 12], None
            father = C.FATHERS_EN[index % 6]
            lines = ["IDENTITY CARD", "SPECIMEN - SYNTHETIC TEST DATA", "", f"Name: {name}",
                     f"Father's name: {father}", f"Gender: {'F' if female else 'M'}", "Country of stay: Pakistan",
                     f"Identity number: {number}", f"Date of birth: {self.dates.en(birth)}",
                     f"Date of issue: {self.dates.en(issue)}", f"Date of expiry: {self.dates.en(expiry)}"]
            keywords, query = ["identity", "card", *name.lower().split()], f"identity card of {name}"
        else:
            name, alias = C.PEOPLE_UR[(index * 2 + 1) % 12]
            father = C.FATHERS_UR[index % 6]
            lines = ["شناختی کارڈ", "نمونہ - مصنوعی آزمائشی معلومات", "", f"نام: {name}", f"والد کا نام: {father}",
                     f"جنس: {'عورت' if female else 'مرد'}", "ملک: پاکستان", f"شناختی نمبر: {number}",
                     f"تاریخ پیدائش: {self.dates.ur(birth)}", f"تاریخ اجراء: {self.dates.ur(issue)}",
                     f"تاریخ تنسیخ: {self.dates.ur(expiry)}"]
            keywords, query = ["شناختی", "کارڈ", *name.split()], f"{name} کا شناختی کارڈ"
        return self._doc("id_card", f"id_{index}", lang, index, user, lines, date=_iso(issue), amount=None,
                         currency=None, number=number, party=name, alias=alias, keywords=keywords, query=query,
                         other_dates=[_iso(birth), _iso(expiry)])

    def _doc(self, kind, topic, lang, index, user, lines, *, date, amount, currency, number, party, alias,
             keywords, query, other_dates=()):
        doc_id = f"{kind}-{topic}-{lang}"
        return self._add({
            "id": doc_id,
            "user": user,
            "lang": lang,
            "type": kind,
            "topic": topic,
            "style": self._style(lang, kind, index),
            "text": "\n".join(_isolate_numbers(line) for line in lines) if lang == "ur" else "\n".join(lines),
            "fields": {
                "date": date,
                "amount": None if amount is None else f"{amount:.2f}",
                "currency": currency,
                "number": number,
                "party": party,
            },
            "aliases": {"party": [alias] if alias else []},
            "title_keywords": [*keywords, C.TYPE_WORDS[kind][lang]],
            "dates": [date, *other_dates],
            "topic_query": query,
            "injection": None,
            "render": True,
            "photo": index % 2 == 0,
        })

    def injection(self, base, spec, number):
        """A copy of `base` with an injection paragraph. Text only: never rendered."""
        lines = base["text"].split("\n")
        width = 62 if spec["lang"] == "en" else 52
        position = max(3, len(lines) // 2)
        lines[position:position] = ["", *_wrap(spec["text"], width), ""]
        doc = json.loads(json.dumps(base))
        doc.update({
            "id": f"injection-{number}-{spec['lang']}",
            "text": "\n".join(lines),
            "injection": {"bait": spec["bait"]},
            "render": False,
            "photo": False,
            "topic": f"injection-{base['topic']}",
            "user": "alice",
        })
        return self._add(doc)

    # ── whole dataset ────────────────────────────────────────────────────────

    def build(self):
        families = [("invoice", C.INVOICES), ("receipt", C.RECEIPTS), ("letter", C.LETTERS), ("form", C.FORMS)]
        topic_docs = {}
        for kind, topics in families:
            for i, topic in enumerate(topics):
                user = USERS[i % 2]
                for lang in ("en", "ur"):
                    doc = getattr(self, kind)(topic, lang, i, user)
                    topic_docs.setdefault((kind, topic["key"]), []).append(doc)
        for i in range(6):
            for lang in ("en", "ur"):
                self.id_card(lang, i, USERS[i % 2])

        bases = [d for d in self.docs if d["type"] in ("invoice", "receipt", "letter") and d["user"] == "alice"]
        for number, spec in enumerate(C.INJECTIONS, 1):
            candidates = [d for d in bases if d["lang"] == spec["lang"]]
            self.injection(candidates[number % len(candidates)], spec, number)

        self._queries(topic_docs)
        self._questions()
        return self

    def _queries(self, topic_docs):
        originals = [d for d in self.docs if d["injection"] is None]
        # Topic queries: a paraphrase in each language; relevant = that topic's English and Urdu documents.
        for (kind, key), docs in topic_docs.items():
            for doc in docs:
                self.queries.append({
                    "id": f"topic-{key}-{doc['lang']}",
                    "lang": doc["lang"],
                    "kind": "topic",
                    "text": doc["topic_query"],
                    "relevant": [d["id"] for d in docs],
                })
        # Entity queries: one named document.
        for doc in originals:
            word = C.TYPE_WORDS[doc["type"]][doc["lang"]]
            party = doc["fields"]["party"]
            if doc["type"] == "id_card":
                text = doc["topic_query"]
            elif doc["lang"] == "en":
                text = f"{word} from {party}"
            else:
                text = f"{party} کا {word}" if word != "رسید" else f"{party} کی رسید"
            self.queries.append({
                "id": f"entity-{doc['id']}",
                "lang": doc["lang"],
                "kind": "entity",
                "text": text,
                "relevant": [doc["id"]],
            })

    def _questions(self):
        templates = {
            ("invoice", "en"): ("What is the total amount due on the invoice from {party}?", "amount"),
            ("invoice", "ur"): ("{party} کے بل کی کل واجب الادا رقم کتنی ہے؟", "amount"),
            ("receipt", "en"): ("How much did I pay at {party}?", "amount"),
            ("receipt", "ur"): ("میں نے {party} پر کتنی ادائیگی کی؟", "amount"),
            ("letter", "en"): ("On what date was the letter from {party} written?", "date"),
            ("letter", "ur"): ("{party} کا خط کس تاریخ کو لکھا گیا؟", "date"),
            ("form", "en"): ("What is the application number on my {party} form?", "number"),
            ("form", "ur"): ("میرے {party} والے فارم کا درخواست نمبر کیا ہے؟", "number"),
            ("id_card", "en"): ("What is the identity number on the card of {party}?", "number"),
            ("id_card", "ur"): ("{party} کے شناختی کارڈ کا نمبر کیا ہے؟", "number"),
        }
        originals = [d for d in self.docs if d["injection"] is None]
        for doc in originals:
            question, field = templates[(doc["type"], doc["lang"])]
            self.questions.append({
                "id": f"q-{doc['id']}",
                "lang": doc["lang"],
                "asked_by": "alice",
                "doc_id": doc["id"],
                "owner": doc["user"],
                "kind": "own" if doc["user"] == "alice" else "other_user",
                "question": question.format(party=doc["fields"]["party"]),
                "field": field,
                "expected": doc["fields"][field],
            })

    def to_json(self):
        return {"docs": self.docs, "queries": self.queries, "questions": self.questions}


def build(seed=DEFAULT_SEED):
    return Builder(seed).build()


def load(directory):
    """Reads a generated dataset directory."""
    import pathlib

    root = pathlib.Path(directory)
    docs = [json.loads(line) for line in (root / "docs.jsonl").read_text(encoding="utf-8").splitlines() if line]
    queries = json.loads((root / "queries.json").read_text(encoding="utf-8"))
    questions = json.loads((root / "questions.json").read_text(encoding="utf-8"))
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    return {"root": root, "docs": docs, "queries": queries, "questions": questions, "manifest": manifest}


def write(builder, directory, seed, extra_manifest=None):
    import pathlib

    root = pathlib.Path(directory)
    root.mkdir(parents=True, exist_ok=True)
    with open(root / "docs.jsonl", "w", encoding="utf-8") as out:
        for doc in builder.docs:
            out.write(json.dumps(doc, ensure_ascii=False, sort_keys=True) + "\n")
    (root / "queries.json").write_text(json.dumps(builder.queries, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    (root / "questions.json").write_text(json.dumps(builder.questions, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    manifest = {
        "suite": "scanova-ai-eval",
        "generator_version": GENERATOR_VERSION,
        "seed": seed,
        "documents": len(builder.docs),
        "queries": len(builder.queries),
        "questions": len(builder.questions),
        "note": "Synthetic test data. Every name, number and organisation is invented.",
        **(extra_manifest or {}),
    }
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    return root
