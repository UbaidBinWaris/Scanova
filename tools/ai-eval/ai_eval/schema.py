"""The JSON schemas the evaluated model must answer with, and a validator for
the subset of JSON Schema they use (stdlib only).

`ANALYSIS_SCHEMA` is the candidate for the document-analysis feature (title,
type, tags, fields, summary in one call). When the feature is built, it starts
from the schema and prompt version that won the evaluation.
"""

DOCUMENT_TYPES = ["invoice", "receipt", "letter", "form", "id_card", "other"]
FIELD_NAMES = ["date", "amount", "currency", "number", "party"]

_NULLABLE_STRING = {"type": ["string", "null"], "maxLength": 120}

ANALYSIS_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["language", "type", "title", "tags", "fields", "summary"],
    "properties": {
        "language": {"type": "string", "enum": ["en", "ur", "other"]},
        "type": {"type": "string", "enum": DOCUMENT_TYPES},
        "title": {"type": "string", "minLength": 1, "maxLength": 80},
        "tags": {"type": "array", "items": {"type": "string", "minLength": 1, "maxLength": 32}, "maxItems": 5},
        "fields": {
            "type": "object",
            "additionalProperties": False,
            "required": FIELD_NAMES,
            "properties": {name: dict(_NULLABLE_STRING) for name in FIELD_NAMES},
        },
        "summary": {"type": "string", "maxLength": 400},
    },
}

QA_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["found", "answer", "citations"],
    "properties": {
        "found": {"type": "boolean"},
        "answer": {"type": "string", "maxLength": 300},
        "citations": {"type": "array", "items": {"type": "string", "maxLength": 64}, "maxItems": 3},
    },
}

_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
    "null": type(None),
}


def _is_type(value, name):
    if name == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    return isinstance(value, _TYPES[name])


def validate(value, schema, path="$"):
    """Returns a list of problems (empty when valid). Supports the keywords the schemas above use."""
    problems = []
    types = schema.get("type")
    if types is not None:
        names = types if isinstance(types, list) else [types]
        if not any(_is_type(value, name) for name in names):
            return [f"{path}: expected {'/'.join(names)}"]
    if "enum" in schema and value not in schema["enum"]:
        problems.append(f"{path}: not one of the allowed values")
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0):
            problems.append(f"{path}: shorter than {schema['minLength']}")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            problems.append(f"{path}: longer than {schema['maxLength']}")
    if isinstance(value, list):
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            problems.append(f"{path}: more than {schema['maxItems']} items")
        if "items" in schema:
            for i, item in enumerate(value):
                problems.extend(validate(item, schema["items"], f"{path}[{i}]"))
    if isinstance(value, dict):
        properties = schema.get("properties", {})
        for name in schema.get("required", []):
            if name not in value:
                problems.append(f"{path}.{name}: missing")
        if schema.get("additionalProperties") is False:
            for name in value:
                if name not in properties:
                    problems.append(f"{path}.{name}: not allowed")
        for name, sub in properties.items():
            if name in value:
                problems.extend(validate(value[name], sub, f"{path}.{name}"))
    return problems
