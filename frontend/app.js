const API_BASE =
  window.COACH_API_BASE ||
  (window.location.hostname.includes("localhost")
    ? "http://localhost:8080/api"
    : "https://twinmind-assignment-1vqj.onrender.com/api");
const state = {
  settings: null,
  defaults: null,
  transcriptEntries: [],
  suggestionBatches: [],
  chatMessages: [],
  mediaRecorder: null,
  mediaStream: null,
  audioChunks: [],
  refreshTimerId: null,
  isRecording: false,
};

const MIN_TRANSCRIPT_INTERVAL_SECONDS = 12;

const el = {};

document.addEventListener("DOMContentLoaded", async () => {
  bindElements();
  bindEvents();
  await loadSettings();
  renderAll();
});

function bindElements() {
  [
    "recordButton", "refreshButton", "exportButton", "settingsButton", "closeSettings",
    "cancelSettings", "saveSettings", "resetSettings", "sendButton", "chatInput",
    "transcriptList", "suggestionsList", "chatList", "settingsModal", "toast",
    "transcriptEmpty", "suggestionsEmpty", "chatEmpty", "recordingStatusLabel",
    "recordingStatusText", "batchCount", "refreshMeta"
  ].forEach((id) => {
    el[id] = document.getElementById(id);
  });

  [
    "apiKey", "transcriptionModel", "suggestionsModel", "chatModel", "refreshIntervalSeconds",
    "suggestionsPrompt", "clickPrompt", "chatPrompt", "suggestionsContextChars",
    "expandedContextChars", "chatContextChars"
  ].forEach((id) => {
    el[id] = document.getElementById(id);
  });
}

function bindEvents() {
  el.recordButton.addEventListener("click", toggleRecording);
  el.refreshButton.addEventListener("click", () => refreshSuggestions(true));
  el.exportButton.addEventListener("click", exportSession);
  el.settingsButton.addEventListener("click", openSettings);
  el.closeSettings.addEventListener("click", closeSettings);
  el.cancelSettings.addEventListener("click", closeSettings);
  el.saveSettings.addEventListener("click", saveSettings);
  el.resetSettings.addEventListener("click", resetSettings);
  el.sendButton.addEventListener("click", () => submitChat());
  el.chatInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      submitChat();
    }
  });

  document.querySelectorAll("[data-tab]").forEach((tab) => {
    tab.addEventListener("click", () => switchTab(tab.dataset.tab));
  });
}

async function loadSettings() {
  const response = await fetch(`${API_BASE}/settings/defaults`);
  const data = await response.json();
  state.defaults = data.defaults;
  const saved = localStorage.getItem("coachSettings");
  state.settings = saved ? JSON.parse(saved) : structuredClone(state.defaults);
  hydrateSettingsForm();
  updateRefreshMeta();
}

function hydrateSettingsForm() {
  Object.keys(state.settings).forEach((key) => {
    if (el[key]) {
      el[key].value = state.settings[key];
    }
  });
}

function openSettings() {
  hydrateSettingsForm();
  el.settingsModal.classList.remove("hidden");
}

function closeSettings() {
  el.settingsModal.classList.add("hidden");
}

function switchTab(tabName) {
  document.querySelectorAll("[data-tab]").forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.tab === tabName);
  });

  document.querySelectorAll("[data-panel]").forEach((panel) => {
    panel.classList.toggle("hidden", panel.dataset.panel !== tabName);
  });
}

function saveSettings() {
  state.settings = {
    apiKey: el.apiKey.value.trim(),
    transcriptionModel: el.transcriptionModel.value.trim(),
    suggestionsModel: el.suggestionsModel.value.trim(),
    chatModel: el.chatModel.value.trim(),
    refreshIntervalSeconds: Number(el.refreshIntervalSeconds.value),
    suggestionsContextChars: Number(el.suggestionsContextChars.value),
    expandedContextChars: Number(el.expandedContextChars.value),
    chatContextChars: Number(el.chatContextChars.value),
    suggestionsPrompt: el.suggestionsPrompt.value,
    clickPrompt: el.clickPrompt.value,
    chatPrompt: el.chatPrompt.value,
  };

  localStorage.setItem("coachSettings", JSON.stringify(state.settings));
  updateRefreshMeta();
  restartAutoRefresh();
  closeSettings();
  toast("Settings saved locally.");
}

function resetSettings() {
  state.settings = structuredClone(state.defaults);
  hydrateSettingsForm();
  localStorage.setItem("coachSettings", JSON.stringify(state.settings));
  updateRefreshMeta();
  restartAutoRefresh();
  toast("Defaults restored.");
}

async function toggleRecording() {
  if (state.isRecording) {
    stopRecording();
    return;
  }

  try {
    state.mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    state.isRecording = true;
    updateRecordingUi();
    startRecorder();
    restartAutoRefresh();
    toast("Recording started.");
  } catch (error) {
    toast("Microphone access was blocked.");
  }
}

function startRecorder() {
  if (!state.mediaStream) {
    return;
  }

  state.mediaRecorder = new MediaRecorder(state.mediaStream);
  state.audioChunks = [];

  state.mediaRecorder.ondataavailable = (event) => {
    if (event.data.size > 0) {
      state.audioChunks.push(event.data);
    }
  };

  state.mediaRecorder.onstop = async () => {
    const audioBlob = new Blob(state.audioChunks, { type: "audio/webm" });
    state.audioChunks = [];

    if (audioBlob.size > 0) {
      await transcribeChunk(audioBlob);
    }

    if (state.isRecording && state.mediaStream?.active) {
      startRecorder();
    } else {
      cleanupStream();
    }
  };

  state.mediaRecorder.start();
  window.setTimeout(() => {
    if (state.mediaRecorder?.state === "recording") {
      state.mediaRecorder.stop();
    }
  }, getTranscriptChunkSeconds() * 1000);
}

function stopRecording() {
  state.isRecording = false;
  if (state.mediaRecorder?.state === "recording") {
    state.mediaRecorder.stop();
  } else {
    cleanupStream();
  }
  updateRecordingUi();
  restartAutoRefresh();
  toast("Recording stopped.");
}

function cleanupStream() {
  if (state.mediaStream) {
    state.mediaStream.getTracks().forEach((track) => track.stop());
    state.mediaStream = null;
  }
}

async function transcribeChunk(audioBlob) {
  const formData = new FormData();
  formData.append("apiKey", state.settings.apiKey);
  formData.append("model", state.settings.transcriptionModel);
  formData.append("audio", audioBlob, `chunk-${Date.now()}.webm`);

  try {
    const response = await fetch(`${API_BASE}/transcribe`, {
      method: "POST",
      body: formData,
    });
    const data = await response.json();
    if (data.text?.trim()) {
      state.transcriptEntries.push({ timestamp: data.timestamp, text: data.text.trim() });
      renderTranscript();
      void maybeRefreshSuggestionsAfterTranscript();
    }
  } catch (error) {
    toast("Transcription failed.");
  }
}

async function maybeRefreshSuggestionsAfterTranscript() {
  const shouldRefresh = state.transcriptEntries.length === 1
    || state.transcriptEntries.length % getSuggestionRefreshEveryChunks() === 0;
  if (shouldRefresh) {
    await refreshSuggestions(false);
  }
}

async function refreshSuggestions(isManual) {
  if (!state.transcriptEntries.length) {
    if (isManual) {
      toast("Start speaking first so we have transcript context.");
    }
    return;
  }

  try {
    const response = await fetch(`${API_BASE}/suggestions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        settings: state.settings,
        transcriptEntries: state.transcriptEntries,
        batchNumber: state.suggestionBatches.length + 1,
      }),
    });
    const batch = await response.json();
    state.suggestionBatches.unshift(batch);
    renderSuggestions();
    if (isManual) {
      toast("Suggestions refreshed.");
    }
  } catch (error) {
    toast("Could not generate suggestions.");
  }
}

async function submitChat(prefill = "", suggestionType = "direct_question") {
  const message = prefill || el.chatInput.value.trim();
  if (!message) {
    return;
  }

  state.chatMessages.push({
    role: "user",
    content: message,
    timestamp: formatNow(),
    suggestionType,
  });

  el.chatInput.value = "";
  renderChat();

  try {
    const response = await fetch(`${API_BASE}/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        settings: state.settings,
        transcriptEntries: state.transcriptEntries,
        chatHistory: state.chatMessages,
        suggestionType,
        userMessage: message,
      }),
    });
    const data = await response.json();
    state.chatMessages.push({
      role: "assistant",
      content: data.answer,
      timestamp: data.timestamp,
      suggestionType,
    });
    renderChat();
  } catch (error) {
    toast("Chat request failed.");
  }
}

function renderAll() {
  renderTranscript();
  renderSuggestions();
  renderChat();
  updateRecordingUi();
}

function renderTranscript() {
  el.transcriptEmpty.classList.toggle("hidden", state.transcriptEntries.length > 0);
  el.transcriptList.innerHTML = state.transcriptEntries.map((entry) => `
    <article class="transcript-entry">
      <span class="transcript-time">${escapeHtml(entry.timestamp)}</span>${escapeHtml(entry.text)}
    </article>
  `).join("");
  el.transcriptList.scrollTop = el.transcriptList.scrollHeight;
}

function renderSuggestions() {
  el.batchCount.textContent = String(state.suggestionBatches.length);
  el.suggestionsEmpty.classList.toggle("hidden", state.suggestionBatches.length > 0);
  el.suggestionsList.innerHTML = state.suggestionBatches.map((batch, batchIndex) => `
    <div class="batch-divider">- BATCH ${state.suggestionBatches.length - batchIndex} · ${escapeHtml(batch.timestamp)} -</div>
    ${batch.suggestions.map((card) => `
      <button class="suggestion-card ${batchIndex === 0 ? "current" : "old"}" data-preview="${encodeURIComponent(card.preview)}" data-type="${encodeURIComponent(card.type)}" type="button">
        <span class="card-tag ${tagClass(card.type)}">${escapeHtml(suggestionLabel(card.type))}</span>
        <div class="suggestion-preview">${escapeHtml(card.preview)}</div>
      </button>
    `).join("")}
  `).join("");

  el.suggestionsList.querySelectorAll(".suggestion-card").forEach((button) => {
    button.addEventListener("click", () => {
      submitChat(decodeURIComponent(button.dataset.preview), decodeURIComponent(button.dataset.type));
    });
  });
}

function renderChat() {
  el.chatEmpty.classList.toggle("hidden", state.chatMessages.length > 0);
  el.chatList.innerHTML = state.chatMessages.map((message) => `
    <div class="chat-block">
      <div class="chat-meta">${escapeHtml(message.role)}${message.suggestionType ? ` · ${formatType(message.suggestionType)}` : ""}</div>
      <div class="chat-bubble ${message.role === "assistant" ? "assistant" : "user"}">${formatChatContent(message)}</div>
    </div>
  `).join("");
  el.chatList.scrollTop = el.chatList.scrollHeight;
}

function updateRecordingUi() {
  el.recordingStatusLabel.textContent = state.isRecording ? "LISTENING" : "IDLE";
  el.recordingStatusText.textContent = state.isRecording ? "Recording. Click to pause." : "Stopped. Click to resume.";
  const orb = el.recordButton.querySelector(".mic-orb");
  orb.classList.toggle("recording", state.isRecording);
}

function restartAutoRefresh() {
  if (state.refreshTimerId) {
    clearInterval(state.refreshTimerId);
  }
  if (state.isRecording) {
    state.refreshTimerId = setInterval(() => refreshSuggestions(false), state.settings.refreshIntervalSeconds * 1000);
  }
}

function updateRefreshMeta() {
  el.refreshMeta.textContent = `auto-refresh every ${state.settings.refreshIntervalSeconds}s`;
}

function getTranscriptChunkSeconds() {
  return Math.min(
    state.settings.refreshIntervalSeconds,
    Math.max(MIN_TRANSCRIPT_INTERVAL_SECONDS, Math.floor(state.settings.refreshIntervalSeconds / 2))
  );
}

function getSuggestionRefreshEveryChunks() {
  return Math.max(1, Math.round(state.settings.refreshIntervalSeconds / getTranscriptChunkSeconds()));
}

function exportSession() {
  const payload = {
    exportedAt: new Date().toISOString(),
    transcriptEntries: state.transcriptEntries,
    suggestionBatches: state.suggestionBatches,
    chatMessages: state.chatMessages,
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `twinmind-session-${Date.now()}.json`;
  link.click();
  URL.revokeObjectURL(url);
  toast("Session exported.");
}

function toast(message) {
  el.toast.textContent = message;
  el.toast.classList.remove("hidden");
  clearTimeout(window.toastTimer);
  window.toastTimer = setTimeout(() => {
    el.toast.classList.add("hidden");
  }, 2400);
}

function tagClass(type) {
  return `tag-${String(type).replaceAll("_", "-")}`;
}

function formatType(value) {
  return String(value).replaceAll("_", " ").toUpperCase();
}

function suggestionLabel(type) {
  switch (String(type || "").toLowerCase()) {
    case "question_to_ask":
      return "QUESTION TO ASK";
    case "talking_point":
      return "TALKING POINT";
    case "fact_check":
      return "FACT-CHECK";
    case "answer":
      return "ANSWER";
    case "clarifying_info":
      return "CLARIFYING INFO";
    default:
      return formatType(type || "insight");
  }
}

function formatChatContent(message) {
  if (message.role !== "assistant") {
    return escapeHtml(message.content).replace(/\n/g, "<br>");
  }

  const cleaned = cleanseAssistantContent(message.content);
  return cleaned
    .split("\n")
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) {
        return "<div class=\"chat-gap\"></div>";
      }
      if (/^[-*]\s+/.test(trimmed)) {
        return `<div class="chat-bullet">${escapeHtml(trimmed.replace(/^[-*]\s+/, ""))}</div>`;
      }
      return `<div class="chat-line">${escapeHtml(trimmed)}</div>`;
    })
    .join("");
}

function cleanseAssistantContent(content) {
  const lines = String(content || "")
    .replace(/\r/g, "")
    .replace(/\*\*/g, "")
    .replace(/^#{1,6}\s*/gm, "")
    .split("\n");

  const filtered = [];
  for (const rawLine of lines) {
    let line = rawLine.trim();
    if (!line) {
      filtered.push("");
      continue;
    }

    line = line
      .replace(/^Answer you can give now:?/i, "")
      .replace(/^What you can say next:?/i, "")
      .replace(/^Key points to include:?/i, "")
      .replace(/^Key points to cover:?/i, "")
      .replace(/^Direct answer:?/i, "")
      .replace(/^Ready-to-use response:?/i, "")
      .trim();

    if (!line) {
      continue;
    }

    if (/^".*"$/.test(line) || /^“.*”$/.test(line)) {
      line = line.slice(1, -1).trim();
    }

    filtered.push(line);
  }

  return filtered.join("\n").replace(/\n{3,}/g, "\n\n").trim();
}

function formatNow() {
  return new Date().toLocaleTimeString("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}
