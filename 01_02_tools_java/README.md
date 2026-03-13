# 01_02_tools_java

Java rewrite of `01_02_tools` using the Responses API and function calling.

## What it does

1. Defines two tools: `get_weather` and `send_email`
2. Supports OpenAI and OpenRouter through shared provider logic
3. Maps `webSearch=true` to:
   - OpenAI: `web_search_preview` tool
   - OpenRouter: `:online` model suffix
4. Runs a tool-calling loop (max 5 steps)
5. Prints the final assistant answer

## Required `.env`

Set these in root `.env` (or `4th-devs/.env`):

```env
AI_PROVIDER=openrouter
OPENROUTER_API_KEY=sk-or-v1-...
# or
# AI_PROVIDER=openai
# OPENAI_API_KEY=sk-...

OPENROUTER_HTTP_REFERER=https://example.com
OPENROUTER_APP_NAME=tools-java-demo
```

## Run

From `4th-devs`:

```bash
mvn -q -f 01_02_tools_java/pom.xml exec:java
```

Or from project folder:

```bash
cd 01_02_tools_java
mvn -q exec:java
```
