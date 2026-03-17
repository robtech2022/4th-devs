# Quick Start Guide

## Step 1: Setup Environment

Copy `.env.example` to the parent `4th-devs` directory and rename it to `.env`:

```bash
cp .env.example ../.env
```

Then edit `../. env` and add your API keys:
- `OPENAI_API_KEY` or `OPENROUTER_API_KEY`
- `AG3NTS_API_KEY` (from https://hub.ag3nts.org)

## Step 2: Build

```bash
mvn clean compile
```

## Step 3: Run Server

```bash
mvn exec:java
```

You should see:
```
🚀 Proxy server started on port 8080
📡 Endpoint: http://localhost:8080/
💚 Health check: http://localhost:8080/health
```

## Step 4: Test Locally

In a new terminal:

```powershell
# Run test script
.\test.ps1

# Or test manually
curl -X POST http://localhost:8080/ `
  -H "Content-Type: application/json" `
  -d '{"sessionID":"test1","msg":"Hello!"}'
```

## Step 5: Expose Publicly

### Using ngrok:
```bash
ngrok http 8080
```

Copy the HTTPS URL (e.g., `https://abc123.ngrok-free.app/`)

### Using pinggy:
```bash
ssh -p 443 -R0:localhost:8080 a.pinggy.io
```

## Step 6: Submit to Hub

```bash
curl -X POST https://hub.ag3nts.org/verify `
  -H "Content-Type: application/json" `
  -d '{
    "apikey": "YOUR_AG3NTS_API_KEY",
    "task": "proxy",
    "answer": {
      "url": "https://your-public-url/",
      "sessionID": "test-session-001"
    }
  }'
```

## Troubleshooting

**Problem:** Port 8080 already in use  
**Solution:** Change port in `.env`:
```env
PORT=8081
```

**Problem:** LLM not calling tools  
**Solution:** Try stronger model - edit `App.java` line 6:
```java
private static final String DEFAULT_MODEL = "gpt-4o";
```

**Problem:** Can't find .env file  
**Solution:** .env should be in `4th-devs/` directory, not `exercise_03_java/`

## What To Expect

The operator will:
1. Ask you to check packages
2. Ask you to redirect packages
3. Provide security codes during conversation
4. Test if you act naturally (may ask about weather, food, etc.)
5. Test reactor package redirection

When successful, the operator will provide a secret code to submit.

## Logs

Watch the console for debug output:
- 📨 Incoming requests
- 📝 Session IDs
- 💬 Messages
- ⚠️ Reactor package detections
- ✅ Responses
