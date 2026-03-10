# Exercise 01 - People Filtering & Tagging

AG3NTS task: Filter people data and classify job descriptions using Structured Output.

## Setup

Set environment variables in root `.env`:

```bash
AG3NTS_API_KEY=your_hub_key
OPENROUTER_API_KEY=sk-or-v1-...
AI_PROVIDER=openrouter
AG3NTS_AUTO_SUBMIT=true
```

## Run

```bash
npm start
```

Or from workspace root:

```bash
node exercise_01_js/app.js
```

## What it does

1. Downloads people.csv from HUB
2. Filters: men, age 20-40 in 2026, born in Grudziądz
3. Sends job descriptions to LLM with Structured Output (gpt-4o)
4. Tags jobs with: IT, transport, edukacja, medycyna, praca z ludźmi, praca z pojazdami, praca fizyczna
5. Selects only transport workers
6. Saves payload to `output/people-answer.txt`
7. Submits to HUB if `AG3NTS_AUTO_SUBMIT=true`

## Output

- `output/people-answer.txt` — verify payload
