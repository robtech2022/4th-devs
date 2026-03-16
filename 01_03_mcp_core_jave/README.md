# 01_03_mcp_core_jave

Java recreation of `01_03_mcp_core`.

## What it does

1. Creates an in-memory MCP-like server with tools, resources, and prompts
2. Uses a Java MCP-like client with elicitation and sampling handlers
3. Calls `calculate` and `summarize_with_confirmation`
4. Lists and reads resources and prompts

## Required `.env`

Set these in root `.env` (or `4th-devs/.env`):

```env
AI_PROVIDER=openrouter
OPENROUTER_API_KEY=sk-or-v1-...
# or
# AI_PROVIDER=openai
# OPENAI_API_KEY=sk-...

OPENROUTER_HTTP_REFERER=https://example.com
OPENROUTER_APP_NAME=mcp-core-jave-demo
```

## Run

From `4th-devs`:

```bash
mvn -q -f 01_03_mcp_core_jave/pom.xml exec:java
```

Or from the project folder:

```bash
cd 01_03_mcp_core_jave
mvn -q exec:java
```
