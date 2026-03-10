# exercise_01_python

Python version of the same AG3NTS `people` exercise logic.

## What it does

1. Downloads `people.csv` from HUB.
2. Filters: men, age 20-40 in 2026, born in Grudziądz.
3. Sends filtered job descriptions in one batch to LLM with Structured Output (JSON Schema).
4. Keeps only records tagged with `transport`.
5. Saves payload to `output/people-answer.txt`.
6. Optionally sends payload to `/verify` when auto-submit is enabled.

## Requirements

- Python 3.10+
- Root `.env` file in parent folder (same as Node project)

## .env variables

Use the root `.env` file and set at least:

```env
OPENROUTER_API_KEY=sk-or-v1-...
AI_PROVIDER=openrouter
AG3NTS_API_KEY=your_hub_key
AG3NTS_AUTO_SUBMIT=false
```

## Run

From workspace root:

```bash
python exercise_01_python/app.py
```

From project folder:

```bash
cd exercise_01_python
python app.py
```
