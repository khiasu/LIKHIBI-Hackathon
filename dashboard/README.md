# Likhibi Translation Dashboard

A web dashboard for English ↔ Nagamese translation. Translation engines plug in behind one contract, and the built-in engines are described in the [project README](../README.md).

| Page | What it does |
| --- | --- |
| **Translate** | Two-way translator with engine choice, alternatives, an evidence panel ("why this translation"), compare mode across engines, and ratings and corrections from speakers |
| **Engines** | Registered engines with their status, plus the three steps to connect a new one |
| **Data health** | Live audit of `datasets/`: usable pairs, placeholder rows, impossible verse references, extraction noise, lexicon gloss coverage, readiness targets, and the public datasets still to import |
| **Evaluation** | Runs recorded by the evaluation harness, with per-segment drill-down sorted weakest first |
| **Contributions** | Review queue for corrections. Approved corrections export as a parallel-data TSV |

```
dashboard/
├── backend/    FastAPI app: engine registry and router, SQLite feedback store, data-health and evaluation services
└── frontend/   React + TypeScript + Tailwind (Vite)
nlp_research/translation/
├── engine_api.py        the engine contract (standard library only)
├── evaluate_engine.py   evaluation harness that writes runs for the dashboard
├── bank.py, retrieval.py, text.py, glossary.py   shared example bank, BM25 retrieval, spelling folding, auto-glossary
├── train_nmt.py         LoRA fine-tuning of NLLB-200 for the NMT engine
└── engines/             translation_memory.py, rag_llm.py (+ llm_providers.py), nmt.py
```

## Built-in engines

When `LIKHIBI_ENGINES` is unset, the backend loads all three. Auto mode tries them in this order and skips any engine that is not configured.

| Engine | What it does | Needs |
| --- | --- | --- |
| **Translation memory** (`memory`) | Returns a stored human translation when the input matches one exactly or almost exactly. Approved corrections are used first. Declines everything else, so the next engine answers. | Nothing. Works offline. |
| **LLM with retrieved examples** (`rag-llm`) | Shows a large language model the 24 most similar sentence pairs plus glossary hints, then asks for the translation. Answers are cached. | A Gemini API key, free from Google AI Studio: paste it on the Engines page, or set `GEMINI_API_KEY`. Or set `LIKHIBI_LLM_PROVIDER=openai` with `LIKHIBI_LLM_MODEL` and `LIKHIBI_LLM_BASE_URL` for OpenAI, OpenRouter, Groq or a local Ollama server. |
| **Fine-tuned NLLB** (`nmt`) | NLLB-200 600M with a LoRA adapter trained on the aligned corpus. Offline, GPU or CPU. | The ML environment below, plus a trained adapter in `models/nmt-nllb-lora/`. |

Approved corrections reach the memory and LLM engines on the very next request, without a restart.

### Saving the Gemini key from the dashboard

Paste the key on the Engines page, in the card of the LLM engine. The backend checks it with Google, which spends no translation quota, then saves it in the operating system's credential store: Windows Credential Manager on Windows, the Keychain on macOS. The store encrypts it for your user account. The engine switches on at once, with no restart.

- The key never goes into the project files, the SQLite database, the logs or the browser. The API returns only its last four characters.
- Only the computer running the dashboard can save or remove the key, through `http://localhost`. When `LIKHIBI_ADMIN_TOKEN` is set, the admin token is required instead.
- A saved key takes precedence over `GEMINI_API_KEY`. Remove it on the Engines page, or in Windows Credential Manager under `likhibi`.
- Servers without a credential store, such as most Linux hosts, cannot save keys. Set `GEMINI_API_KEY` there.

## Run it

Requires Python 3.10 or newer (tested on 3.14) and Node.js 20.19 or newer (tested on 24).

On Windows, if `python` opens the Microsoft Store, Windows is using its app alias instead of your installed Python. Either turn off the `python.exe` alias in Settings > Apps > Advanced app settings > App execution aliases, or call your Python by its full path, for example `& "$env:LOCALAPPDATA\Python\bin\python.exe" -m venv .venv`. Once `.venv` exists, every command below uses `.venv\Scripts\python` and needs neither.

**Backend** (PowerShell; on macOS or Linux use `.venv/bin/python`):

```powershell
cd dashboard\backend
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements-dev.txt
$env:GEMINI_API_KEY = "..."   # optional: enables the LLM engine, or paste the key on the Engines page
.venv\Scripts\python -m uvicorn app.main:create_app --factory --reload --port 8000
```

This lightweight environment serves the translation memory and the LLM engine. The NMT engine needs PyTorch, so it runs from the ML environment instead.

**ML environment** for training and serving the NMT engine (about 7 GB including the 2.4 GB base model; put it on a drive with space):

```powershell
python -m venv C:\likhibi-ml
C:\likhibi-ml\Scripts\python -m pip install torch==2.14.1 --index-url https://download.pytorch.org/whl/cu126
C:\likhibi-ml\Scripts\python -m pip install -r nlp_research\requirements-ml.txt -r dashboard\backend\requirements.txt
# train (about 45 minutes on a 4 GB laptop GPU), from the repository root:
C:\likhibi-ml\Scripts\python -m nlp_research.translation.train_nmt --epochs 3
#   --resume continues an interrupted run; on a laptop the run stops and saves if it is
#   unplugged and the battery drops below 35% (--min-battery 0 turns that off).
# serve the dashboard with all three engines, from dashboard\backend:
C:\likhibi-ml\Scripts\python -m uvicorn app.main:create_app --factory --port 8000
```

**Frontend**, in a second terminal:

```powershell
cd dashboard\frontend
npm install
npm run dev        # http://localhost:5173, proxies /api to port 8000
```

**Single-process mode** for demos and deployment: build the frontend once, and FastAPI serves it.

```powershell
cd dashboard\frontend; npm run build
cd ..\backend; .venv\Scripts\python -m uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
# open http://localhost:8000
```

API documentation is generated at `http://localhost:8000/docs`.

## Connect an engine

1. Implement `TranslationEngine` from `nlp_research/translation/engine_api.py`, for example in `nlp_research/translation/engines/my_engine.py`. Set `info` (id, name, kind, version, directions, offline) and implement `translate()`. Override `load()` to load weights once, and `status()` to report a missing API key or model file.
2. Register it and restart the backend:

   ```powershell
   $env:LIKHIBI_ENGINES = "nlp_research.translation.engines.my_engine:MyEngine"
   $env:LIKHIBI_DEFAULT_ENGINE = "my-engine"
   ```

3. Score it on a held-out test set. The run appears on the Evaluation page.

   ```powershell
   cd <repository root>
   dashboard\backend\.venv\Scripts\python -m nlp_research.translation.evaluate_engine `
       --engine nlp_research.translation.engines.my_engine:MyEngine `
       --test-set datasets\processed\test_sets\everyday_test_en_nag.tsv `
       --direction en-nag
   ```

How requests are routed:

- **Auto mode** tries the default engine first, then every other ready engine that supports the direction. The mock engine always goes last. If an engine raises `EngineError`, the next one answers and the response carries a warning saying so.
- **A decline is not a failure.** An engine with no confident answer, such as the translation memory without a match, raises `EngineDeclinedError` and the next engine answers silently. If every engine declines, the API returns HTTP 422 with the code `no_confident_translation`.
- **An explicitly chosen engine** is tried alone. A failure returns HTTP 502 with the engine's message.
- **Engine bugs** (any other exception) are converted into the same clean 502, so they never crash the server.
- **Engines must be thread-safe** and must enforce their own timeouts on remote calls.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `LIKHIBI_ENGINES` | the three built-in engines | Comma-separated engine specs, `package.module:ClassOrFactory`. `none` disables all engines. |
| `LIKHIBI_DEFAULT_ENGINE` | `memory` with the built-ins | Engine id tried first in auto mode |
| `LIKHIBI_TM_THRESHOLD` | 0.95 | Similarity a near match needs before the translation memory answers |
| `GEMINI_API_KEY` or `LIKHIBI_LLM_API_KEY` | none | Enables the LLM engine. A key saved on the Engines page takes precedence. |
| `LIKHIBI_LLM_PROVIDER` | `gemini` | `openai` for any OpenAI-compatible server |
| `LIKHIBI_LLM_MODEL` | `gemini-3.8-flash` | Model name; required for `openai`. `gemini-3.5-flash-lite` is faster. |
| `LIKHIBI_LLM_THINKING` | `low` | Gemini thinking level: `minimal`, `low`, `medium`, `high`, or `default` |
| `LIKHIBI_LLM_BASE_URL` | OpenAI's API | For example `http://localhost:11434/v1` for Ollama, which needs no key |
| `LIKHIBI_LLM_EXAMPLES` | 24 | Retrieved sentence pairs per prompt |
| `LIKHIBI_LLM_CACHE` | `<repo>/.cache/likhibi/llm_cache.sqlite3` | Response cache; `off` disables it |
| `LIKHIBI_NMT_ADAPTER` | `<repo>/models/nmt-nllb-lora` | Trained LoRA adapter for the NMT engine |
| `LIKHIBI_NMT_BASE_MODEL` | local copy, else `facebook/nllb-200-distilled-600M` | Base model path or Hugging Face id |
| `LIKHIBI_NMT_DEVICE` | `cuda` when available | `cpu` forces CPU inference |
| `LIKHIBI_ENABLE_MOCK_ENGINE` | off | `1` registers an engine that echoes input, clearly labelled, for UI work |
| `LIKHIBI_MAX_CHARS` | 2000 | Longest accepted input |
| `LIKHIBI_LOG_TRANSLATIONS` | on | `0` stops storing translated text. The Translate page states whether text is stored. |
| `LIKHIBI_ADMIN_TOKEN` | none | When set, listing, reviewing and exporting feedback need the `X-Admin-Token` header. Set it on any public deployment. |
| `LIKHIBI_DATASETS_DIR` | `<repo>/datasets` | Dataset root that Data health measures |
| `LIKHIBI_DB_PATH` | `backend/var/likhibi.sqlite3` | SQLite file for translations and feedback |
| `LIKHIBI_CORS_ORIGINS` | `http://localhost:5173` | Origins allowed to call the API from a browser |
| `LIKHIBI_FRONTEND_DIST` | `frontend/dist` | Built frontend served at `/` when the folder exists |
| `VITE_API_BASE` (frontend build) | same origin | Set it when the frontend is hosted apart from the API |

## API

| Method and path | Purpose |
| --- | --- |
| `GET /api/health`, `GET /api/meta` | Liveness and server settings the UI needs |
| `GET /api/engines` | Registered engines, their states, and specs that failed to load |
| `POST /api/translate` | `{text, source_lang, target_lang, engine: "auto", num_alternatives}` returns the translation, alternatives, confidence, evidence, warnings and latency |
| `POST /api/translate/compare` | Runs every ready engine on the same input, concurrently |
| `POST /api/feedback` | A rating (`up` or `down`), a corrected translation, or both |
| `GET /api/feedback?status=pending` | Review queue with counts (admin) |
| `PATCH /api/feedback/{id}` | `{status: approved, rejected or pending}` (admin) |
| `GET /api/feedback/export.tsv` | Approved corrections as parallel data (admin) |
| `GET /api/data/health`, `GET /api/data/sources` | Dataset audit and the import checklist |
| `GET /api/settings/llm` | Where the LLM engine's key comes from, and its last four characters |
| `PUT /api/settings/llm-key`, `DELETE /api/settings/llm-key` | Check and save a Gemini key in the credential store, or remove it (this computer or admin) |
| `GET /api/eval/runs`, `GET /api/eval/runs/{id}` | Evaluation runs and their segments |

Errors always have the shape `{"error": {"code", "message", "details"}}`. The Android keyboard can call `POST /api/translate` with the same JSON.

## Data conventions the dashboard reads

- **Parallel data:** `datasets/processed/parallel_corpus/*.tsv` with the columns `english_text` and `nagamese_text`. An optional `domain` column (bible, everyday, news...) splits the counts; without it, a file whose name contains "bible" counts as Bible text.
- **Test sets:** `datasets/processed/test_sets/*.tsv` with the columns `source_text` and `target_text`. Keep them out of training and retrieval.
- **Evaluation runs:** `datasets/evaluations/mt_runs/*.json`, written by the harness.
- **External sources:** each source on the Data health page counts as imported once its folder under `datasets/` holds files.

## Tests

```powershell
cd dashboard\backend
.venv\Scripts\python -m pytest
cd ..\frontend
npm run build      # type-checks with tsc, then bundles
```

The backend tests cover the engine contract, routing and fallback, every error path, feedback review and export, the admin token, saving API keys, data-health measurements, and the evaluation harness end to end.
