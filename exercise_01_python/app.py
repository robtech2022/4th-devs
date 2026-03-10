from __future__ import annotations

import csv
import io
import json
import os
import re
import sys
import unicodedata
from pathlib import Path
from typing import Any
from urllib import request, error

# -----------------------------
# Config
# -----------------------------
ROOT_DIR = Path(__file__).resolve().parent
ENV_FILE = ROOT_DIR.parent / ".env"

TASK_NAME = "people"
HUB_BASE_URL = "https://hub.ag3nts.org"
TARGET_CITY = "grudziadz"
CURRENT_YEAR = 2026
MIN_AGE = 20
MAX_AGE = 40
MIN_BORN_YEAR = CURRENT_YEAR - MAX_AGE
MAX_BORN_YEAR = CURRENT_YEAR - MIN_AGE

TAGS = [
    "IT",
    "transport",
    "edukacja",
    "medycyna",
    "praca z ludźmi",
    "praca z pojazdami",
    "praca fizyczna",
]

TAG_DESCRIPTIONS = {
    "IT": "Praca związana z oprogramowaniem, infrastrukturą IT, analizą danych lub cyberbezpieczeństwem.",
    "transport": "Logistyka, przewóz osób/towarów, spedycja, planowanie tras i łańcucha dostaw.",
    "edukacja": "Nauczanie, szkolenia, dydaktyka, mentoring i rozwój kompetencji.",
    "medycyna": "Opieka zdrowotna, diagnostyka, leczenie, pielęgniarstwo, ratownictwo.",
    "praca z ludźmi": "Obsługa klienta, doradztwo, HR, sprzedaż, role wymagające częstego kontaktu interpersonalnego.",
    "praca z pojazdami": "Prowadzenie, serwis, naprawa lub operowanie pojazdami i sprzętem transportowym.",
    "praca fizyczna": "Praca manualna lub terenowa, wymagająca wysiłku fizycznego.",
}

TAGGING_SCHEMA = {
    "name": "job_tagging_results",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "results": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "integer"},
                        "tags": {
                            "type": "array",
                            "items": {
                                "type": "string",
                                "enum": TAGS,
                            },
                        },
                    },
                    "required": ["id", "tags"],
                    "additionalProperties": False,
                },
            }
        },
        "required": ["results"],
        "additionalProperties": False,
    },
}


def load_env_file(path: Path) -> None:
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key and key not in os.environ:
            os.environ[key] = value


def resolve_provider() -> str:
    requested = os.getenv("AI_PROVIDER", "").strip().lower()
    openai_key = os.getenv("OPENAI_API_KEY", "").strip()
    openrouter_key = os.getenv("OPENROUTER_API_KEY", "").strip()

    if requested and requested not in {"openai", "openrouter"}:
        raise RuntimeError("AI_PROVIDER must be one of: openai, openrouter")

    if requested == "openai":
        if not openai_key:
            raise RuntimeError("AI_PROVIDER=openai requires OPENAI_API_KEY")
        return "openai"

    if requested == "openrouter":
        if not openrouter_key:
            raise RuntimeError("AI_PROVIDER=openrouter requires OPENROUTER_API_KEY")
        return "openrouter"

    if openai_key:
        return "openai"
    if openrouter_key:
        return "openrouter"

    raise RuntimeError("No API key set. Add OPENAI_API_KEY or OPENROUTER_API_KEY")


def resolve_model_for_provider(provider: str, model: str) -> str:
    if provider == "openrouter" and "/" not in model and model.startswith("gpt-"):
        return f"openai/{model}"
    return model


def provider_config() -> dict[str, Any]:
    provider = resolve_provider()
    endpoints = {
        "openai": "https://api.openai.com/v1/chat/completions",
        "openrouter": "https://openrouter.ai/api/v1/chat/completions",
    }

    api_key = os.getenv("OPENAI_API_KEY", "").strip() if provider == "openai" else os.getenv("OPENROUTER_API_KEY", "").strip()
    headers: dict[str, str] = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    }

    if provider == "openrouter":
        referer = os.getenv("OPENROUTER_HTTP_REFERER", "").strip()
        app_name = os.getenv("OPENROUTER_APP_NAME", "").strip()
        if referer:
            headers["HTTP-Referer"] = referer
        if app_name:
            headers["X-Title"] = app_name

    return {
        "provider": provider,
        "endpoint": endpoints[provider],
        "headers": headers,
    }


def http_json(method: str, url: str, headers: dict[str, str], payload: dict[str, Any] | None = None) -> dict[str, Any]:
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")

    req = request.Request(url=url, method=method, headers=headers, data=data)

    try:
        with request.urlopen(req) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = {"error": {"message": raw[:400]}}
        message = parsed.get("error", {}).get("message", f"HTTP {exc.code}")
        raise RuntimeError(f"{message} (status: {exc.code})") from exc


def normalize_text(value: Any) -> str:
    text = str(value or "")
    text = unicodedata.normalize("NFD", text)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    return text.strip().lower()


def parse_csv(csv_text: str) -> list[dict[str, str]]:
    sample = "\n".join(line for line in csv_text.splitlines() if line.strip())[:2000]

    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
    except Exception:
        dialect = csv.excel

    reader = csv.DictReader(io.StringIO(csv_text), dialect=dialect)
    rows: list[dict[str, str]] = []
    for row in reader:
        cleaned: dict[str, str] = {}
        for k, v in row.items():
            key = (k or "").replace("\ufeff", "").strip()
            cleaned[key] = (v or "").strip()
        if any(cleaned.values()):
            rows.append(cleaned)
    return rows


def normalize_gender(value: Any) -> str:
    normalized = normalize_text(value)
    if normalized in {"m", "male", "mezczyzna", "mężczyzna"}:
        return "M"
    if normalized in {"f", "female", "kobieta"}:
        return "F"
    return str(value or "").strip().upper()


def parse_born_year(value: Any) -> int | None:
    raw = str(value or "").strip()
    if not raw:
        return None

    match = re.search(r"(19|20)\d{2}", raw)
    if match:
        return int(match.group(0))

    try:
        return int(raw)
    except ValueError:
        return None


def map_person_record(raw_record: dict[str, str]) -> dict[str, Any]:
    def get(*keys: str) -> str:
        for rk, rv in raw_record.items():
            normalized_key = normalize_text(rk)
            if any(normalized_key == normalize_text(k) for k in keys):
                return rv
        return ""

    return {
        "name": get("name", "imie"),
        "surname": get("surname", "nazwisko", "last_name"),
        "gender": normalize_gender(get("gender", "plec", "sex")),
        "born": parse_born_year(get("born", "birth_year", "rok_urodzenia", "birthDate", "data_urodzenia")),
        "city": get("city", "birth_city", "miasto", "miasto_urodzenia", "birthPlace", "miejsce_urodzenia"),
        "job": get("job", "occupation", "stanowisko", "opis_pracy"),
    }


def is_born_in_target_city(city: Any) -> bool:
    normalized_city = normalize_text(city)
    return normalized_city == TARGET_CITY or TARGET_CITY in normalized_city


def is_candidate(person: dict[str, Any]) -> bool:
    if person.get("gender") != "M":
        return False

    born = person.get("born")
    if not isinstance(born, int) or born < MIN_BORN_YEAR or born > MAX_BORN_YEAR:
        return False

    return is_born_in_target_city(person.get("city", ""))


def tag_jobs(candidates: list[dict[str, Any]], cfg: dict[str, Any], model: str) -> list[dict[str, Any]]:
    if not candidates:
        return []

    jobs_list = "\n".join(f"{idx}: {p.get('job') or 'brak opisu'}" for idx, p in enumerate(candidates))
    tags_guide = "\n".join(f"- {tag}: {TAG_DESCRIPTIONS[tag]}" for tag in TAGS)

    input_text = "\n".join([
        "Otaguj opisy stanowisk pracy.",
        "Użyj wyłącznie tagów z listy i przypisz 0..N tagów do każdego rekordu.",
        "Nie zgaduj ponad opis; jeśli brak podstaw, zwróć pustą listę tagów.",
        "Tagi:",
        tags_guide,
        "",
        "Rekordy:",
        jobs_list,
    ])

    request_body = {
        "model": model,
        "messages": [{"role": "user", "content": input_text}],
        "response_format": {
            "type": "json_schema",
            "json_schema": TAGGING_SCHEMA,
        },
    }

    print("[tagJobs] Using endpoint:", cfg["endpoint"])
    data = http_json("POST", cfg["endpoint"], cfg["headers"], request_body)

    output_text = None
    choices = data.get("choices")
    if isinstance(choices, list) and choices:
        output_text = choices[0].get("message", {}).get("content")

    if not output_text:
        raise RuntimeError("Missing output in API response")

    parsed = json.loads(output_text) if isinstance(output_text, str) else output_text
    results = parsed.get("results") if isinstance(parsed, dict) else None
    if not isinstance(results, list):
        raise RuntimeError("Invalid tagging result: missing results array")

    id_to_tags: dict[int, list[str]] = {}
    for item in results:
        if isinstance(item, dict) and isinstance(item.get("id"), int) and isinstance(item.get("tags"), list):
            id_to_tags[item["id"]] = [str(tag) for tag in item["tags"]]

    merged: list[dict[str, Any]] = []
    for index, candidate in enumerate(candidates):
        merged.append({**candidate, "tags": id_to_tags.get(index, [])})
    return merged


def fetch_people_csv(hub_api_key: str) -> str:
    if not hub_api_key:
        raise RuntimeError("Missing AG3NTS_API_KEY (or HUB_API_KEY) in .env")

    csv_url = f"{HUB_BASE_URL}/data/{hub_api_key}/people.csv"
    req = request.Request(url=csv_url, method="GET")
    try:
        with request.urlopen(req) as resp:
            return resp.read().decode("utf-8")
    except error.HTTPError as exc:
        raise RuntimeError(f"Failed to fetch people.csv (status: {exc.code})") from exc


def submit_answer(hub_api_key: str, answer: list[dict[str, Any]]) -> dict[str, Any]:
    payload = {
        "apikey": hub_api_key,
        "task": TASK_NAME,
        "answer": answer,
    }
    headers = {"Content-Type": "application/json"}
    return http_json("POST", f"{HUB_BASE_URL}/verify", headers, payload)


def save_payload_to_file(payload: dict[str, Any]) -> None:
    out_dir = ROOT_DIR / "output"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / "people-answer.txt"
    out_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    load_env_file(ENV_FILE)

    cfg = provider_config()
    provider = cfg["provider"]
    model = resolve_model_for_provider(provider, "gpt-4o")

    print("[AI config] requested provider:", os.getenv("AI_PROVIDER", "").strip() or "(auto)")
    print("[AI config] resolved provider:", provider)
    print("[AI config] chat endpoint:", cfg["endpoint"])

    hub_api_key = (os.getenv("AG3NTS_API_KEY", "").strip() or os.getenv("HUB_API_KEY", "").strip())
    auto_submit = os.getenv("AG3NTS_AUTO_SUBMIT", "false").strip().lower() == "true"

    csv_text = fetch_people_csv(hub_api_key)
    raw_rows = parse_csv(csv_text)
    print("CSV data rows:", len(raw_rows))

    people = [map_person_record(row) for row in raw_rows]

    men = [p for p in people if p.get("gender") == "M"]
    men_with_age = [p for p in men if isinstance(p.get("born"), int) and MIN_BORN_YEAR <= p["born"] <= MAX_BORN_YEAR]
    men_with_age_city = [p for p in men_with_age if is_born_in_target_city(p.get("city", ""))]

    print("Men:", len(men))
    print(f"Men age {MIN_AGE}-{MAX_AGE} in {CURRENT_YEAR}:", len(men_with_age))
    print("Men age-matching and born in Grudziądz:", len(men_with_age_city))

    candidates = [p for p in people if is_candidate(p)]
    tagged_candidates = tag_jobs(candidates, cfg, model)

    answer: list[dict[str, Any]] = []
    for person in tagged_candidates:
        tags = person.get("tags", [])
        if "transport" in tags:
            answer.append({
                "name": person.get("name", ""),
                "surname": person.get("surname", ""),
                "gender": person.get("gender", ""),
                "born": person.get("born", None),
                "city": person.get("city", ""),
                "tags": tags,
            })

    payload = {
        "apikey": hub_api_key,
        "task": TASK_NAME,
        "answer": answer,
    }

    save_payload_to_file(payload)

    print("Candidates after demographic filtering:", len(candidates))
    print("Final transport matches:", len(answer))
    print("Saved payload:", str(ROOT_DIR / "output" / "people-answer.txt"))

    if not auto_submit:
        print("Auto-submit is disabled. Set AG3NTS_AUTO_SUBMIT=true to submit automatically.")
        return

    result = submit_answer(hub_api_key, answer)
    print("Verify response:", json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
