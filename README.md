# TwinMind Live Suggestions Copilot

A full-stack meeting copilot inspired by the TwinMind assignment. The app captures microphone audio in the browser, builds a live transcript, generates live suggestion cards, and turns those suggestions into detailed chat answers with transcript context.

The project is split into a separate frontend and backend so the UI can evolve independently from the Java API.

## Stack

- Frontend: HTML, CSS, vanilla JavaScript
- Backend: Java 17, Spring Boot 3.3.5, Maven
- AI provider: Groq
- Transcription: `whisper-large-v3`
- Suggestions model default: `openai/gpt-oss-120b`
- Chat model default: `openai/gpt-oss-120b`

## What It Does

- Captures microphone audio in the browser
- Sends audio chunks to the backend for transcription
- Shows a live transcript in the left panel
- Generates exactly 3 live suggestions per refresh batch
- Supports suggestion types such as `question_to_ask`, `talking_point`, `fact_check`, `answer`, and `clarifying_info`
- Lets users click a suggestion to generate a detailed answer in chat
- Supports direct chat questions with transcript context
- Stores settings locally in browser localStorage
- Exports the current session as JSON
- Falls back to demo transcript behavior when no Groq API key is provided

## Project Structure

```text
twinmind/
├─ backend/    Spring Boot API
├─ frontend/   static frontend
└─ README.md
```

Important backend files:

- `backend/pom.xml`
- `backend/src/main/java/com/twinmind/coachcopilot/CoachCopilotApplication.java`
- `backend/src/main/java/com/twinmind/coachcopilot/controller/CopilotController.java`
- `backend/src/main/java/com/twinmind/coachcopilot/service/CopilotService.java`
- `backend/src/main/java/com/twinmind/coachcopilot/service/GroqClient.java`
- `backend/src/main/java/com/twinmind/coachcopilot/service/DefaultSettingsFactory.java`
- `backend/src/main/resources/application.yml`

Important frontend files:

- `frontend/index.html`
- `frontend/styles.css`
- `frontend/app.js`

## API Overview

Base path: `/api`

- `GET /api/settings/defaults`
  Returns default UI and prompt settings.

- `POST /api/transcribe`
  Accepts multipart audio and returns transcript text with a timestamp.

- `POST /api/suggestions`
  Generates a live suggestion batch from recent transcript context.

- `POST /api/chat`
  Generates a detailed answer using transcript and session chat context.

## Local Development

### Prerequisites

- Java 17
- Maven
- A modern browser with microphone access
- Optional: Groq API key for real transcription and model responses

### 1. Start the backend

```bash
cd backend
mvn spring-boot:run
```

Backend runs on:

```text
http://localhost:8080
```

### 2. Start the frontend

Serve the `frontend/` folder with any static file server.

Examples:

- VS Code Live Server
- `npx serve frontend`
- any local static hosting tool

By default, the frontend calls:

```text
http://localhost:8080/api
```

## Configuration

Current backend config is in `backend/src/main/resources/application.yml`.

Default values:

- Port: `8080`
- Multipart max file size: `25MB`
- Multipart max request size: `25MB`
- Groq base URL: `https://api.groq.com`
- Default refresh interval: `30` seconds

Frontend API base is currently defined in `frontend/app.js`:

```js
const API_BASE = window.COACH_API_BASE || "http://localhost:8080/api";
```

That means production can be handled in one of these ways:

- serve frontend and backend behind the same domain and use `/api`
- inject `window.COACH_API_BASE`
- update the frontend to point to the deployed backend URL

## Product Behavior Notes

- Microphone access happens in the browser, so deployed frontend should use HTTPS
- Settings are stored locally in the browser
- The user’s Groq API key is entered in the settings modal
- No login or database is required
- Chat history is session-only in the browser
- Export is client-side JSON export

## CORS

The backend currently allows CORS for `/api/**` and accepts:

- `GET`
- `POST`
- `OPTIONS`

This is configured in:

- `backend/src/main/java/com/twinmind/coachcopilot/config/WebConfig.java`

## Running Tests

From the backend folder:

```bash
mvn test
```

## Deployment Notes

The app is deployment-friendly because the frontend and backend are already separated.

Typical deployment shape:

- frontend on a static host such as Vercel, Netlify, or Cloudflare Pages
- backend on a JVM-friendly host such as Render, Railway, or Fly.io

Before deployment, make sure to:

- deploy the backend with Java 17
- expose the backend over HTTPS
- point the frontend to the deployed backend API
- verify browser microphone access works on the deployed frontend URL

## Current Status

Implemented and working:

- separated frontend and backend
- premium dark TwinMind-style UI
- transcript panel
- live suggestions panel
- detailed answers chat panel
- settings modal with prompts and advanced controls
- demo fallback without API key
- improved suggestion balancing
- recency-aware suggestion context
- session export

## License

This project was built as an assignment/demo-style application. Add your preferred license before publishing publicly.
