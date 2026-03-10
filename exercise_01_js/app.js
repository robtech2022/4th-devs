import {
  AI_API_KEY,
  AI_PROVIDER,
  EXTRA_API_HEADERS,
  RESPONSES_API_ENDPOINT,
  resolveModelForProvider
} from "../config.js";
import { mkdir, writeFile } from "node:fs/promises";
import { extractResponseText } from "../01_01_structured/helpers.js";

// Model used for job-tag classification (must support structured output via response_format json_schema).
const MODEL = resolveModelForProvider("gpt-4o");

// Task constants from the exercise statement.
const TASK_NAME = "people";
const HUB_BASE_URL = "https://hub.ag3nts.org";
const TARGET_CITY = "grudziadz";

// Age constraints: men aged 20-40 in year 2026.
const CURRENT_YEAR = 2026;
const MIN_AGE = 20;
const MAX_AGE = 40;
const MIN_BORN_YEAR = CURRENT_YEAR - MAX_AGE;
const MAX_BORN_YEAR = CURRENT_YEAR - MIN_AGE;

// Allowed tags from the assignment.
const TAGS = [
  "IT",
  "transport",
  "edukacja",
  "medycyna",
  "praca z ludźmi",
  "praca z pojazdami",
  "praca fizyczna"
];

// Short descriptions to improve LLM tagging quality.
const TAG_DESCRIPTIONS = {
  IT: "Praca związana z oprogramowaniem, infrastrukturą IT, analizą danych lub cyberbezpieczeństwem.",
  transport: "Logistyka, przewóz osób/towarów, spedycja, planowanie tras i łańcucha dostaw.",
  edukacja: "Nauczanie, szkolenia, dydaktyka, mentoring i rozwój kompetencji.",
  medycyna: "Opieka zdrowotna, diagnostyka, leczenie, pielęgniarstwo, ratownictwo.",
  "praca z ludźmi": "Obsługa klienta, doradztwo, HR, sprzedaż, role wymagające częstego kontaktu interpersonalnego.",
  "praca z pojazdami": "Prowadzenie, serwis, naprawa lub operowanie pojazdami i sprzętem transportowym.",
  "praca fizyczna": "Praca manualna lub terenowa, wymagająca wysiłku fizycznego."
};

// Hub key used for downloading CSV and optionally sending /verify.
const HUB_API_KEY = process.env.AG3NTS_API_KEY?.trim()
  ?? process.env.HUB_API_KEY?.trim()
  ?? "";

// If false: save payload only. If true: also call /verify.
const AUTO_SUBMIT = (process.env.AG3NTS_AUTO_SUBMIT?.trim().toLowerCase() ?? "false") === "true";

// Output file with the final payload expected by HUB verify endpoint.
const OUTPUT_FILE_URL = new URL("./output/people-answer.txt", import.meta.url);

// JSON Schema passed to the model through response_format.json_schema.
// The model must return an object: { results: [{ id, tags[] }, ...] }.
const taggingSchema = {
  name: "job_tagging_results",
  strict: true,
  schema: {
    type: "object",
    properties: {
      results: {
        type: "array",
        items: {
          type: "object",
          properties: {
            id: { type: "integer" },
            tags: {
              type: "array",
              items: {
                type: "string",
                enum: TAGS
              }
            }
          },
          required: ["id", "tags"],
          additionalProperties: false
        }
      }
    },
    required: ["results"],
    additionalProperties: false
  }
};

// Normalizes text for robust comparisons:
// - removes diacritics (e.g. "Grudziądz" -> "grudziadz")
// - trims + lowercases
function normalizeText(value) {
  return String(value ?? "")
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .trim()
    .toLowerCase();
}

  // Heuristic delimiter detection for CSV files with unknown separators.
function detectCsvDelimiter(csvText) {
  const firstLine = csvText
    .split(/\r?\n/)
    .find((line) => line.trim().length > 0) ?? "";

  const candidates = [",", ";", "\t", "|"];
  const separatorCounts = candidates.map((separator) => ({
    separator,
    count: firstLine.split(separator).length - 1
  }));

  separatorCounts.sort((a, b) => b.count - a.count);
  return separatorCounts[0]?.count > 0 ? separatorCounts[0].separator : ",";
}

// Minimal CSV parser supporting:
// - quoted values
// - escaped quotes ("")
// - CRLF/LF newlines
// - BOM in header
function parseCsv(csvText) {
  const delimiter = detectCsvDelimiter(csvText);
  const rows = [];
  let currentRow = [];
  let currentField = "";
  let insideQuotes = false;

  for (let i = 0; i < csvText.length; i += 1) {
    const char = csvText[i];
    const next = csvText[i + 1];

    if (char === '"') {
      if (insideQuotes && next === '"') {
        currentField += '"';
        i += 1;
      } else {
        insideQuotes = !insideQuotes;
      }

      continue;
    }

    if (!insideQuotes && char === delimiter) {
      currentRow.push(currentField);
      currentField = "";
      continue;
    }

    if (!insideQuotes && (char === "\n" || char === "\r")) {
      if (char === "\r" && next === "\n") {
        i += 1;
      }

      currentRow.push(currentField);
      currentField = "";

      if (currentRow.some((value) => value.trim() !== "")) {
        rows.push(currentRow);
      }

      currentRow = [];
      continue;
    }

    currentField += char;
  }

  if (currentField.length > 0 || currentRow.length > 0) {
    currentRow.push(currentField);
    if (currentRow.some((value) => value.trim() !== "")) {
      rows.push(currentRow);
    }
  }

  if (rows.length === 0) {
    return [];
  }

  const [header, ...body] = rows;

  // Convert row arrays into objects keyed by header columns.
  return body.map((row) => {
    const record = {};
    header.forEach((columnName, index) => {
      record[String(columnName).replace(/^\uFEFF/, "").trim()] = row[index]?.trim() ?? "";
    });
    return record;
  });
}

// Normalizes multiple gender representations into "M"/"F".
function normalizeGender(value) {
  const normalized = normalizeText(value);
  if (["m", "male", "mezczyzna", "mezczyzna"].includes(normalized)) {
    return "M";
  }

  if (["f", "female", "kobieta"].includes(normalized)) {
    return "F";
  }

  return String(value ?? "").trim().toUpperCase();
}

// Extracts a year from either a full date (YYYY-MM-DD) or a plain year.
function parseBornYear(value) {
  const raw = String(value ?? "").trim();
  if (!raw) {
    return Number.NaN;
  }

  const yearMatch = raw.match(/(19|20)\d{2}/);
  if (yearMatch) {
    return Number.parseInt(yearMatch[0], 10);
  }

  return Number.parseInt(raw, 10);
}

// Maps raw CSV fields (possibly multilingual / alternate names)
// into the canonical person shape used by the pipeline.
function mapPersonRecord(rawRecord) {
  const get = (...keys) => {
    const entry = Object.entries(rawRecord).find(([key]) =>
      keys.some((candidate) => normalizeText(key) === normalizeText(candidate))
    );
    return entry ? entry[1] : "";
  };

  return {
    name: get("name", "imie"),
    surname: get("surname", "nazwisko", "last_name"),
    gender: normalizeGender(get("gender", "plec", "sex")),
    born: parseBornYear(get("born", "birth_year", "rok_urodzenia", "birthDate", "data_urodzenia")),
    city: get("city", "birth_city", "miasto", "miasto_urodzenia", "birthPlace", "miejsce_urodzenia"),
    job: get("job", "occupation", "stanowisko", "opis_pracy")
  };
}

// City check is intentionally tolerant in case of additional words/formatting.
function isBornInTargetCity(city) {
  const normalizedCity = normalizeText(city);
  return normalizedCity === TARGET_CITY || normalizedCity.includes(TARGET_CITY);
}

// Applies demographic conditions from the exercise:
// 1) male 2) age 20-40 in 2026 3) born in Grudziądz.
function isCandidate(person) {
  if (person.gender !== "M") {
    return false;
  }

  if (!Number.isInteger(person.born) || person.born < MIN_BORN_YEAR || person.born > MAX_BORN_YEAR) {
    return false;
  }

  return isBornInTargetCity(person.city);
}

// Sends one batch request to LLM and gets tags for each candidate job.
async function tagJobs(candidates) {
  if (candidates.length === 0) {
    return [];
  }

  // Numbered records allow stable mapping: id -> tags.
  const jobsList = candidates
    .map((person, index) => `${index}: ${person.job || "brak opisu"}`)
    .join("\n");

  const tagsGuide = TAGS
    .map((tag) => `- ${tag}: ${TAG_DESCRIPTIONS[tag]}`)
    .join("\n");

  const input = [
    "Otaguj opisy stanowisk pracy.",
    "Użyj wyłącznie tagów z listy i przypisz 0..N tagów do każdego rekordu.",
    "Nie zgaduj ponad opis; jeśli brak podstaw, zwróć pustą listę tagów.",
    "Tagi:",
    tagsGuide,
    "",
    "Rekordy:",
    jobsList
  ].join("\n");

  // Chat Completions payload with structured output enforced by JSON schema.
  const requestBody = {
    model: MODEL,
    messages: [
      {
        role: "user",
        content: input
      }
    ],
    response_format: {
      type: "json_schema",
      json_schema: taggingSchema
    }
  };

  // We derive Chat Completions URL from configured Responses endpoint.
  const chatCompletionsEndpoint = RESPONSES_API_ENDPOINT.replace("/responses", "/chat/completions");
  console.log("[tagJobs] Using endpoint:", chatCompletionsEndpoint);

  const response = await fetch(chatCompletionsEndpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${AI_API_KEY}`,
      ...EXTRA_API_HEADERS
    },
    body: JSON.stringify(requestBody)
  });

  const rawBody = await response.text();
  let data = {};

  try {
    data = JSON.parse(rawBody);
  } catch {
    // Keep {} to trigger a meaningful error below.
    data = {};
  }

  if (!response.ok || data.error) {
    console.error("[tagJobs] Full API response body:", rawBody.slice(0, 500));
    console.error("[tagJobs] Parsed error object:", data);
    const apiMessage = data?.error?.message ?? rawBody?.slice(0, 300) ?? "Unknown API error";
    throw new Error(`[tagJobs][${AI_PROVIDER}] ${apiMessage} (status: ${response.status}, model: ${MODEL})`);
  }

  // Chat Completions content path + fallback for compatibility.
  const outputText = data.choices?.[0]?.message?.content ?? extractResponseText(data);

  if (!outputText) {
    console.error("[tagJobs] Response structure:", JSON.stringify(data, null, 2).slice(0, 500));
    throw new Error("Missing output in API response");
  }

  const parsed = typeof outputText === "string" ? JSON.parse(outputText) : outputText;
  if (!Array.isArray(parsed.results)) {
    throw new Error(`Invalid tagging result: missing or non-array results (got: ${typeof parsed.results})`);
  }

  // Convert list into map for efficient join by candidate index.
  const idToTags = new Map(parsed.results.map((item) => [item.id, item.tags]));

  // Merge model tags back into original candidate records.
  return candidates.map((candidate, index) => ({
    ...candidate,
    tags: idToTags.get(index) ?? []
  }));
}

// Downloads source CSV from HUB.
async function fetchPeopleCsv() {
  if (!HUB_API_KEY) {
    throw new Error("Missing AG3NTS_API_KEY (or HUB_API_KEY) in .env");
  }

  const csvUrl = `${HUB_BASE_URL}/data/${HUB_API_KEY}/people.csv`;
  const response = await fetch(csvUrl);
  if (!response.ok) {
    throw new Error(`Failed to fetch people.csv (status: ${response.status})`);
  }

  return response.text();
}

// Optional final call to HUB verify endpoint.
async function submitAnswer(answer) {
  const response = await fetch(`${HUB_BASE_URL}/verify`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      apikey: HUB_API_KEY,
      task: TASK_NAME,
      answer
    })
  });

  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = data?.message ?? `Verify request failed (status: ${response.status})`;
    throw new Error(message);
  }

  return data;
}

// Persists payload locally so you can inspect/edit before submission.
async function savePayloadToFile(payload) {
  await mkdir(new URL("./output/", import.meta.url), { recursive: true });
  await writeFile(OUTPUT_FILE_URL, `${JSON.stringify(payload, null, 2)}\n`, "utf-8");
}

// Main orchestration:
// fetch -> parse -> filter -> tag -> select transport -> save -> (optional) submit
async function main() {
  const csvText = await fetchPeopleCsv();
  const rawRows = parseCsv(csvText);
  console.log("CSV data rows:", rawRows.length);
  const people = rawRows.map(mapPersonRecord);

  // Diagnostic counters to quickly validate filtering behavior.
  const men = people.filter((person) => person.gender === "M");
  const menWithAge = men.filter((person) => Number.isInteger(person.born) && person.born >= MIN_BORN_YEAR && person.born <= MAX_BORN_YEAR);
  const menWithAgeFromCity = menWithAge.filter((person) => isBornInTargetCity(person.city));

  console.log("Men:", men.length);
  console.log(`Men age ${MIN_AGE}-${MAX_AGE} in ${CURRENT_YEAR}:`, menWithAge.length);
  console.log("Men age-matching and born in Grudziądz:", menWithAgeFromCity.length);

  const candidates = people.filter(isCandidate);
  const taggedCandidates = await tagJobs(candidates);

  // We only keep people tagged with "transport".
  const answer = taggedCandidates
    .filter((person) => person.tags.includes("transport"))
    .map((person) => ({
      name: person.name,
      surname: person.surname,
      gender: person.gender,
      born: person.born,
      city: person.city,
      tags: person.tags
    }));

  const payload = {
    apikey: HUB_API_KEY,
    task: TASK_NAME,
    answer
  };

  // Always save payload, even when auto-submit is enabled.
  await savePayloadToFile(payload);

  console.log("Candidates after demographic filtering:", candidates.length);
  console.log("Final transport matches:", answer.length);
  console.log("Saved payload:", OUTPUT_FILE_URL.pathname);

  if (!AUTO_SUBMIT) {
    console.log("Auto-submit is disabled. Set AG3NTS_AUTO_SUBMIT=true to submit automatically.");
    return;
  }

  // Send to HUB only when explicitly enabled.
  const result = await submitAnswer(answer);
  console.log("Verify response:", JSON.stringify(result, null, 2));
}

// Top-level error handling for cleaner CLI output.
main().catch((error) => {
  console.error(`Error: ${error.message}`);
  process.exit(1);
});
