# exercise_02_java

Java solution for task `findhim`.

This implementation uses **Function Calling** (tool-calling) with an agent loop (max 15 iterations).

## What it does

1. Loads suspects from previous task output (`people-answer.txt`).
2. Downloads power-plant locations from:
   - `https://hub.ag3nts.org/data/<apikey>/findhim_locations.json`
3. Calls `/api/location` for each suspect.
4. Computes distance to each power plant using Haversine.
5. Selects the globally closest suspect-to-plant match.
6. Calls `/api/accesslevel` for that suspect.
7. Saves verify payload to `output/findhim-answer.txt`.
8. Optionally submits to `/verify` if `AG3NTS_AUTO_SUBMIT=true`.

## Required `.env`

Use root `.env` with at least:

```env
AG3NTS_API_KEY=...
AG3NTS_AUTO_SUBMIT=false
OPENROUTER_API_KEY=...   # or OPENAI_API_KEY
AI_PROVIDER=openrouter   # or openai
AGENT_MODEL=gpt-5-mini   # optional, default gpt-5-mini
```

## Run

From workspace root:

```bash
mvn -q -f exercise_02_java/pom.xml exec:java
```

From project folder:

```bash
cd exercise_02_java
mvn -q exec:java
```
