# Likhibi event runbook

Everything to run Likhibi at the venue: the laptop serves the dashboard and the stronger models, the phone runs the keyboard and the offline translator, and attendees contribute through a QR code. Commands are for Windows PowerShell from the repository root unless a step says otherwise. `$ml` stands for the Python of the ML environment described in the README, for example `$ml = "C:\likhibi-ml\Scripts\python.exe"`.

## The night before

1. **Install the tools.**
   ```powershell
   winget install --id Cloudflare.cloudflared
   winget install --id Genymobile.scrcpy
   ```
2. **Build and install the keyboard.**
   - Connect the phone with USB debugging on.
   - Run:
     ```powershell
     $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
     .\gradlew :app:installDebug
     ```
   - On the phone: Likhibi → Enable Keyboard → Select Keyboard.
3. **Check the offline translator on the phone in airplane mode.** Settings › Translation should say "Installed, … MB. Works without internet". In any text field, tap the logo, then Translate, and type "Where is the market?".
4. **Rehearse the demo three times** with a timer. Record one clean run as a backup video: Windows Game Bar (Win+Alt+R) while `scrcpy` mirrors the phone.
5. **Charge everything.** The laptop runs the GPU model; keep it plugged in.

## At the venue, in this order

### 1. Start the dashboard server (laptop)

```powershell
cd dashboard\backend
$env:LIKHIBI_ADMIN_TOKEN = "choose-a-long-random-phrase"   # protects review and export
$env:LIKHIBI_LOG_TRANSLATIONS = "0"                        # visitors' translations are not stored
$env:LIKHIBI_DB_PATH = "$PWD\var\event.sqlite3"
& $ml -m uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
```

Wait for `Application startup complete` (about 20 seconds; the NMT model loads onto the GPU). Then open http://localhost:8000 on the laptop.

If the Gemini key is not saved yet, paste it on the Engines page. Saving a key only works from the laptop itself.

### 2. Open the public link

In a second PowerShell window:

```powershell
cloudflared tunnel --url http://localhost:8000
```

It prints an address like `https://something-random.trycloudflare.com`. That address changes every time cloudflared starts, so start it once and leave it running.

### 3. Print or show the QR card

```powershell
cd path\to\LIKHIBI-Hackathon
dashboard\backend\.venv\Scripts\python tools\make_qr.py https://something-random.trycloudflare.com
```

This creates `tools\likhibi_contribute_qr.png`, an A6 card that links to the Contribute page. Print two copies for the table, or show the image on a second screen.

### 4. Point the phone at the server

On the phone, open Likhibi → Translation:
- set **Server address** to the cloudflared address;
- for the demo, switch on **Use the Likhibi server when online**, so the stronger translation follows the offline one automatically.

The HTTPS link works on any network, including mobile data.

### 5. Mirror the phone for the audience

```powershell
scrcpy --max-size 1280 --window-title "Likhibi" --stay-awake
```

Drag the window onto the projector screen beside the dashboard.

### 6. Back up contributions every hour

In a third PowerShell window:

```powershell
cd path\to\LIKHIBI-Hackathon
New-Item -ItemType Directory -Force backups | Out-Null
while ($true) { Copy-Item dashboard\backend\var\event.sqlite3 ("backups\event-" + (Get-Date -Format "yyyyMMdd-HHmm") + ".sqlite3"); Start-Sleep -Seconds 3600 }
```

## The live data drive (Day 2)

- **The ask:** "Add three things you say every day, and check three that other people added." Every phrase is checked by a second speaker before Likhibi uses it.
- **Who to ask:** other teams, volunteers, judges between sessions, family on the phone.
- **The rules we follow:**
  - Every contributor chooses how their phrase is shared (openly under CC BY-SA, or Likhibi only) and whether to be credited.
  - Nothing is published without that choice.
  - Never put words in anyone's mouth: contributors type their own phrases.
- **Evening of Day 2:** rebuild the keyboard vocabulary and score the engines on what came in. The commands are in "After the drive" below.

## After the drive

```powershell
cd path\to\LIKHIBI-Hackathon
# Score every engine on the everyday sentences speakers verified (the dashboard writes the test set):
& $ml -m nlp_research.translation.evaluate_engine --engine nlp_research.translation.engines.nmt:NmtEngine --test-set datasets\processed\test_sets\everyday_test_en_nag.tsv --direction en-nag --test-set-name everyday-event
& $ml -m nlp_research.translation.evaluate_engine --engine nlp_research.translation.engines.student:StudentEngine --test-set datasets\processed\test_sets\everyday_test_en_nag.tsv --direction en-nag --test-set-name everyday-event
```

Present the everyday numbers exactly as they come out, even if they are low. The point is that the community is now measuring and improving it.

## If something breaks

| Problem | What to do |
| --- | --- |
| Venue Wi-Fi is down | Turn on the phone's hotspot and connect the laptop to it. cloudflared works over the hotspot. Offline, the keyboard's prediction and translation still work. |
| cloudflared will not start | Use the laptop's local address instead. Run `ipconfig` and read the IPv4 address; on the phone set the server to `http://192.168.x.x:8000`. The debug build allows plain HTTP on the local network. |
| The GPU runs out of memory | Close other Python windows. Restart the server; the NMT model needs about 2 GB of GPU memory. |
| The phone mirror fails | Play the backup video, and pass the phone to the judges. |
| A translation comes out wrong on stage | Say so, and use it: "This is exactly why the Teach page exists." Then add the correct sentence through Teach Likhibi. |
| The keyboard does not appear | Settings › System › Keyboard: choose Likhibi again. Long-press the space bar to switch keyboards. |

## Shutting down

1. Stop cloudflared (Ctrl+C) so the public link closes.
2. Stop the server (Ctrl+C).
3. Copy `dashboard\backend\var\event.sqlite3` to the backups folder.
4. Export the approved contributions from the Contributions page. That export is the community's dataset, so share it back with the contributors.
