# 01_03_mcp_translator_java

Java recreation of `01_03_mcp_translator`.

## What it does

1. Starts a local MCP-like files server rooted at `workspace/`
2. Watches `workspace/translate/` for supported files
3. Translates incoming files to English and writes them to `workspace/translated/`
4. Exposes `POST /api/chat` and `POST /api/translate`

## Required `.env`

Set these in root `.env` or `4th-devs/.env`:

```env
AI_PROVIDER=openrouter
OPENROUTER_API_KEY=sk-or-v1-...
# or
# AI_PROVIDER=openai
# OPENAI_API_KEY=sk-...

OPENROUTER_HTTP_REFERER=https://example.com
OPENROUTER_APP_NAME=mcp-translator-java-demo
```

## Run

From `4th-devs`:

```bash
mvn -q -f 01_03_mcp_translator_java/pom.xml exec:java
```

Or from the project folder:

```bash
cd 01_03_mcp_translator_java
mvn -q exec:java
```

## Example curl

```bash
curl -X POST "http://localhost:3000/api/translate" -H "Content-Type: application/json" -d '{"text":"To jest przykladowy tekst po polsku."}'
```
