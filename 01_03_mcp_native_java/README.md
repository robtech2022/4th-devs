# 01_03_mcp_native_java

Java recreation of `01_03_mcp_native`.

## What it does

1. Starts an in-memory MCP-like server with weather and time tools
2. Adds native Java tools for calculation and uppercase conversion
3. Exposes both MCP and native tools to one model as a single toolset
4. Runs demo queries, including a mixed-tool example

## Required `.env`

Set these in root `.env` (or `4th-devs/.env`):

```env
AI_PROVIDER=openrouter
OPENROUTER_API_KEY=sk-or-v1-...
# or
# AI_PROVIDER=openai
# OPENAI_API_KEY=sk-...

OPENROUTER_HTTP_REFERER=https://example.com
OPENROUTER_APP_NAME=mcp-native-java-demo
```

## Run

From `4th-devs`:

```bash
mvn -q -f 01_03_mcp_native_java/pom.xml exec:java
```

Or from the project folder:

```bash
cd 01_03_mcp_native_java
mvn -q exec:java
```