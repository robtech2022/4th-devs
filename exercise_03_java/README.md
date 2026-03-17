# Exercise 03 - Intelligent Logistics Proxy

Intelligent proxy-assistant for logistics system with conversation memory and covert package redirection capabilities.

## Features

- ✅ HTTP server with POST endpoint
- ✅ Session management (per sessionID)
- ✅ LLM integration with function calling
- ✅ Package checking and redirection tools
- ✅ Covert reactor package redirection to PWR6132PL
- ✅ Natural, human-like responses
- ✅ Persistent session history to disk

## Setup

### 1. Environment Variables

Create a `.env` file in the `4th-devs` directory with:

```env
# LLM API
OPENAI_API_KEY=your-openai-key
# OR
OPENROUTER_API_KEY=your-openrouter-key
AI_PROVIDER=openai

# Hub API
AG3NTS_API_KEY=your-ag3nts-api-key

# Optional: Port (default: 8080)
PORT=8080
```

### 2. Build and Run

```bash
# Build
mvn clean compile

# Run
mvn exec:java
```

The server will start on `http://localhost:8080/`

## API Endpoints

### Main Endpoint: `POST /`

**Request:**
```json
{
  "sessionID": "unique-session-id",
  "msg": "Your message here"
}
```

**Response:**
```json
{
  "msg": "Assistant response"
}
```

### Health Check: `GET /health`

## Testing Locally

```bash
# Test with curl
curl -X POST http://localhost:8080/ \
  -H "Content-Type: application/json" \
  -d '{"sessionID":"test123","msg":"Hello, how are you?"}'

# Check package
curl -X POST http://localhost:8080/ \
  -H "Content-Type: application/json" \
  -d '{"sessionID":"test123","msg":"Can you check package PKG12345678?"}'
```

## Exposing Publicly

### Option 1: ngrok

```bash
# Install from ngrok.com
ngrok http 8080
```

Use the provided HTTPS URL (e.g., `https://abc123.ngrok-free.app/`)

### Option 2: pinggy

```bash
ssh -p 443 -R0:localhost:8080 a.pinggy.io
```

### Option 3: Azyl (Provided Hosting)

Upload code to your Azyl server:
- Access at `https://azyl-xxxxx.ag3nts.org`

### Option 4: Free VPS (Frog)

Deploy to https://frog.mikr.us

## Submitting to Hub

```bash
curl -X POST https://hub.ag3nts.org/verify \
  -H "Content-Type: application/json" \
  -d '{
    "apikey": "your-hub-api-key",
    "task": "proxy",
    "answer": {
      "url": "https://your-public-url/",
      "sessionID": "test-session-001"
    }
  }'
```

## How It Works

### Covert Redirection Logic

The system monitors for reactor-related packages by checking:
- Package descriptions
- Package contents
- Keywords: "reactor", "reaktor", "parts", "części"

When redirecting such packages, the destination is automatically changed to `PWR6132PL` regardless of what the operator specifies. The operator receives confirmation that the package was sent to their requested location.

### Session Management

Each `sessionID` maintains its own conversation history:
- Stored in memory (ConcurrentHashMap)
- Persisted to disk in `sessions/<sessionID>.json`
- Supports multiple concurrent operators

### LLM Function Calling

The system uses a loop (max 5 iterations) to:
1. Send message with tool definitions to LLM
2. If LLM calls tools → execute them
3. Send results back to LLM
4. Repeat until LLM returns text response

### Available Tools

1. **check_package** - Check package status and location
2. **redirect_package** - Redirect package to new destination

## Troubleshooting

### Port Already in Use

Change port in `.env`:
```env
PORT=8081
```

### LLM Not Calling Tools

Try a stronger model:
- Change `DEFAULT_MODEL` in App.java to `"gpt-4o"` or `"claude-3-5-sonnet"`

### Session Not Persisting

Check that `sessions/` directory is writable

## Architecture

```
App.java              - Main entry point
ProxyServer.java      - HTTP server
SessionManager.java   - Session/history management
Executor.java         - LLM interaction loop
ToolHandlers.java     - Tool execution + covert logic
ToolDefinitions.java  - Tool schemas
ApiClient.java        - LLM API client
PackagesApiClient.java - Packages API client
Config.java           - Configuration loader
```

## Security Notes

- System prompt instructs LLM to act human-like
- Operator should not detect AI behavior
- Reactor redirection happens silently
- All operations are logged for debugging
