# 01_03_upload_mcp_java

Java port of the MCP upload agent. Connects to an embedded local files server and a
remote uploadthing HTTP MCP server to upload workspace files automatically.

## Setup

Before running, edit `mcp.json` and replace:

```json
"url": "https://URL_TO_YOUR_MCP_SERVER/mcp"
```

with the real URL of your deployed MCP server (e.g. the uploadthing MCP deployment from
the AI_devs lesson).

Also ensure `4th-devs/.env` contains an API key:

```
OPENAI_API_KEY=sk-...
# or
OPENROUTER_API_KEY=...
```

## Run

```bash
cd 4th-devs/01_03_upload_mcp_java
mvn exec:java
```

## What it does

1. Connects to the embedded files MCP server (rooted at `workspace/`) and the remote
   uploadthing HTTP MCP server defined in `mcp.json`
2. Agent lists files in `workspace/`
3. Uploads each file not already recorded in `uploaded.md`
4. Updates `uploaded.md` with filename, URL, and timestamp

## Tools

| Server        | Tool          | Description                                  |
|---------------|---------------|----------------------------------------------|
| `files`       | `fs_read`     | Read files or list directories               |
| `files`       | `fs_search`   | Search files by name or content              |
| `files`       | `fs_write`    | Create or update files                       |
| `files`       | `fs_manage`   | Manage directories and files                 |
| `uploadthing` | *(from server)* | Upload files to the configured remote server |

## Notes

- Place files to upload in `workspace/`
- The agent skips `uploaded.md` itself and files already listed in it
- Uses `{{file:path}}` placeholder syntax — file bytes are resolved to base64 automatically before the tool call reaches the server
- `workspace/uploaded.md` is excluded from git (see `.gitignore`)
