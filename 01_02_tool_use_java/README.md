# 01_02_tool_use_java

Java rewrite of `01_02_tool_use` using the Responses API and sandboxed filesystem tools.

## What it does

1. Defines 6 filesystem tools (`list_files`, `read_file`, `write_file`, `delete_file`, `create_directory`, `file_info`)
2. Resets `sandbox/` to an empty state before running
3. Runs each example query as a separate conversation
4. Executes tool calls and appends `function_call_output` items until final text
5. Blocks path traversal so all file operations stay inside sandbox

## Required `.env`

Set these in root `.env` (or `4th-devs/.env`):

```env
AI_PROVIDER=openrouter
OPENROUTER_API_KEY=sk-or-v1-...
# or
# AI_PROVIDER=openai
# OPENAI_API_KEY=sk-...

OPENROUTER_HTTP_REFERER=https://example.com
OPENROUTER_APP_NAME=tool-use-java-demo
```

## Run

From `4th-devs`:

```bash
mvn -q -f 01_02_tool_use_java/pom.xml exec:java
```

Or from the project folder:

```bash
cd 01_02_tool_use_java
mvn -q exec:java
```
