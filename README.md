
# 📖 AI Web Novel Summarizer & Audiobook Player

> AI-powered reading companion — browse, extract, summarise, and listen to web novels with cross-device cloud sync.

![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)
![Language](https://img.shields.io/badge/Language-Kotlin-purple?style=flat-square&logo=kotlin)
![Architecture](https://img.shields.io/badge/Architecture-MVVM-blue?style=flat-square)
![Cloud](https://img.shields.io/badge/Cloud-Firebase-orange?style=flat-square&logo=firebase)
![AI](https://img.shields.io/badge/AI-Multi--Provider%20%2B%20Custom-red?style=flat-square)
![Min SDK](https://img.shields.io/badge/Min%20SDK-API%2028-blue?style=flat-square)

---

## 📖 Overview

A specialised Android application that embeds a full-featured web browser — pre-configured for major novel platforms with an aggressive ad-blocker and dark/light CSS injection — and can summarise any chapter with a single tap. 

Beyond summarisation, the app features a **fully offline Audiobook Player** that converts pasted text or `.txt` files into spoken audio with background playback, seeking, and speed controls. All library data, summaries, and audiobooks can be securely synced across devices using **Firebase Firestore** with custom GZIP compression to bypass document size limits.

Users can utilise built-in ultra-fast AI models (**Cerebras GPT-OSS-120B**, **Groq Llama 3.3**, **Gemini**) or connect **any OpenAI-compatible custom API endpoint** directly from the Settings UI.

---

## ✨ Core Features

| Feature | Description |
|---|---|
| 🌐 **Smart WebView Browser** | Full browser with URL detection, search routing, back/forward, and refresh. |
| 📚 **Pre-configured Novel Sites** | One-tap access to WebNovel, WuxiaWorld, RoyalRoad, ScribbleHub, and more. |
| 🤖 **Multi-Provider AI** | Built-in Cerebras, Groq, and Gemini. Auto-fallback routing for rate limits. |
| 🔌 **Custom AI Endpoints** | Add your own OpenAI-compatible APIs (OpenRouter, LM Studio, etc.) via UI. |
| 🎧 **Audiobook Player (TTS)** | Import `.txt` or paste text. Background playback, seek, skip, speed (0.75x-2x). |
| ☁️ **Cloud Synchronization** | Firebase Auth + Firestore. Syncs Novels, Chapters, Bookmarks, and Audiobooks. |
| 🗜️ **GZIP Cloud Compression** | Compresses large audiobook texts to bypass Firestore's 1MB document limit. |
| 🚫 **15+ Domain Ad Blocker** | Blocks ad networks, trackers, popups, and cookie banners via JS injection. |
| 🌙 **Light/Dark Mode Injection** | Algorithmic darkening and custom CSS injection for comfortable reading. |
| 📂 **Hierarchical Library** | Novels → Volumes → Chapters structure with full CRUD and swipe actions. |
| 🕒 **History & Bookmarks** | Auto-tracked page visits and one-tap bookmarks with duplicate detection. |
| 📴 **Offline-First Architecture** | All data is stored locally. Cloud sync is strictly opt-in. |

---

## 🛠️ Tech Stack

- **Language:** Kotlin
- **Architecture:** MVVM (ViewModel + StateFlow + Repository + Foreground Services)
- **Local DB:** Room Database (5 tables with DAOs)
- **Local Storage:** `java.io.File` (Audiobooks), `SharedPreferences` (Positions/Prefs), Gson
- **Cloud / Auth:** Firebase Authentication, Cloud Firestore
- **AI Providers:** Cerebras API, Groq API, Google Gemini, Custom OpenAI-compatible
- **Networking:** Retrofit 2 + OkHttp 4 (with custom API key interceptors)
- **Audio / Media:** Android `TextToSpeech`, `AudioManager`, `NotificationCompat`
- **Async:** Kotlin Coroutines, Flow, `lifecycleScope`
- **Browser:** Android WebView + JavaScript DOM manipulation
- **Min SDK:** API 28 (Android 9) | **Target SDK:** API 36 (Android 14)

---

## 🏗️ Architecture & Project Structure

```text
app/src/main/java/com/example/novel_summary/
├── data/
│   ├── local/          # Room AppDatabase, DAOs, Entities, Converters
│   ├── network/        # Retrofit interfaces, OkHttp interceptors, API models
│   └── repository/     # Single source of truth for data operations
├── service/
│   └── AudiobookTtsService.kt  # Foreground service for background TTS playback
├── ui/
│   ├── adapter/        # RecyclerView adapters for Library, History, Bookmarks
│   ├── audiobook/      # Audiobook Manager, Import, and Player Activities
│   ├── customai/       # Custom AI API Manager UI
│   ├── viewmodel/      # ViewModels for Activities (Summary, Library, Browser)
│   └── [Activities]    # MainActivity, SettingsActivity, ActivitySummary, etc.
└── utils/
    ├── audiobook/      # AudiobookStore, SpeechUnitSplitter, PositionStore, Compression
    ├── customai/       # Custom AI API JSON storage and validation
    └── [Helpers]       # SyncManager, WebViewUtils, UrlUtils, ToastUtils, NetworkUtils
```

---

## 🗄️ Data & Storage Schema

### 1. Room Database (Local Structured Data)
```sql
history_table      → id, url, title, timestamp
bookmarks_table    → id, url, title, timestamp
novels_table       → id, name (UNIQUE)
volumes_table      → id, novel_id (FK), volume_name
chapters_table     → id, volume_id (FK), chapter_name, summary_text, summary_type, timestamp
```
*CASCADE delete on all foreign keys — deleting a novel removes all its volumes and chapters automatically.*

### 2. Local File Storage (Large Unstructured Data)
```text
/files/audiobooks/
  ├── index.json           # Metadata (id, title, charCount, createdAt, updatedAt)
  ├── book_xxxxx.txt       # Raw text files (up to 1,000,000 chars)
  └── custom_ai_apis.json  # User-defined custom AI endpoints
```

### 3. Cloud Firestore (Opt-in Sync)
```text
users/{userId}/
  ├── novels/{novelId}/volumes/{volumeId}/chapters/{chapterId}
  ├── bookmarks/{bookmarkId}
  └── audiobooks/{bookId}  # Contains metadata + GZIP/Base64 compressed textData
```

---

## 💡 Implementation Highlights

### 1. Multi-Stage Content Extraction
Content extraction runs a JavaScript pipeline injected directly into the WebView to isolate the main text from cluttered web pages:
```javascript
(function() {
    // Stage 1: Remove noise
    ['script','style','nav','header','footer','aside','.ads','.popup']
      .forEach(sel => document.querySelectorAll(sel).forEach(el => el.remove()));

    // Stage 2: Try ranked selectors
    const selectors = ['.chapter-content', '.entry-content', '.content',
                       '.novel-content', 'article', 'main', '[role="main"]'];
    let best = null, bestLen = 0;
    selectors.forEach(sel => {
        const el = document.querySelector(sel);
        if (el && el.innerText.length > bestLen) { best = el; bestLen = el.innerText.length; }
    });

    // Stage 3: Paragraph-density fallback
    if (!best || bestLen < 200) {
        const paras = [...document.querySelectorAll('p')]
            .filter(p => p.innerText.length > 30 && p.querySelectorAll('a').length <= 2);
        if (paras.length) return paras.map(p => p.innerText).join('\n\n');
    }
    return best ? best.innerText.trim() : document.body.innerText.trim();
})()
```

### 2. GZIP Compression for Audiobook Cloud Sync
Firestore has a strict **1MB document size limit**. A 300,000-character audiobook easily exceeds this. To solve this without relying on Firebase Storage, the `SyncManager` compresses the text using **GZIP** and encodes it in **Base64** before upload.
* A 300KB text file compresses to ~80KB.
* The sync engine checks the cloud `updatedAt` timestamp and only uploads the heavy compressed payload if the book is new or locally modified, saving bandwidth.

### 3. Robust Speech Unit Splitter
To allow accurate seeking in the Audiobook Player, raw text cannot be fed to the TTS engine as one block. The `SpeechUnitSplitter` breaks text into 300–800 character units based on paragraph and sentence boundaries, avoiding splits on decimals (`3.14`), abbreviations (`Dr.`), and ellipses (`...`). This creates a stable index for the `SeekBar` to map to.

### 4. Foreground TTS Service
The `AudiobookTtsService` runs as an Android Foreground Service with a persistent notification. It manages AudioFocus (pausing for phone calls), handles utterance callbacks to save the exact character offset to `SharedPreferences`, and survives configuration changes and screen locks.

---

## 📸 Screenshots

<p align="center">
  <img src="https://github.com/sudiptoroy7666-lgtm/portfolio/blob/fbc009ea41d89c1956497af02910183aa3dd1ecc/assets/screenshots/webnovel/sum1.jpg" width="16%"/>
  <img src="https://github.com/sudiptoroy7666-lgtm/portfolio/blob/fbc009ea41d89c1956497af02910183aa3dd1ecc/assets/screenshots/webnovel/sum2.jpg" width="16%"/>
  <img src="https://github.com/sudiptoroy7666-lgtm/portfolio/blob/fbc009ea41d89c1956497af02910183aa3dd1ecc/assets/screenshots/webnovel/sum3.jpg" width="16%"/>
  <img src="https://github.com/sudiptoroy7666-lgtm/portfolio/blob/fbc009ea41d89c1956497af02910183aa3dd1ecc/assets/screenshots/webnovel/sum4.jpg" width="16%"/>
  <img src="https://github.com/sudiptoroy7666-lgtm/portfolio/blob/fbc009ea41d89c1956497af02910183aa3dd1ecc/assets/screenshots/webnovel/sum5.jpg" width="16%"/>
  <img src="https://github.com/sudiptoroy7666-lgtm/portfolio/blob/fbc009ea41d89c1956497af02910183aa3dd1ecc/assets/screenshots/webnovel/sum6.jpg" width="16%"/>
</p>
*(Screenshots represent the core browsing and summarisation flow. Audiobook and Settings UI follow the same Glassmorphism design language.)*

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or later
- A Firebase Project (for Auth and Firestore)
- *(Optional)* API keys for Cerebras, Groq, or Gemini (Users can also supply their own via the app UI)

### Setup

1. **Clone the repository**
   ```bash
   git clone <your-repo-url>
   cd Novel_Summary
   ```

2. **Firebase Configuration**
   - Go to Firebase Console -> Add Android App (`com.example.novel_summary`).
   - Download `google-services.json` and place it in the `app/` directory.
   - Enable **Email/Password Authentication**.
   - Create a **Cloud Firestore** database.

3. **Add Built-in API Keys in `local.properties`** *(Optional)*
   ```properties
   GROQ_API_KEY_PRIMARY=your_groq_key
   GROQ_API_KEY_FALLBACK=your_groq_fallback_key
   CEREBRAS_API_KEY=your_cerebras_key
   GEMINI_API_KEY=your_gemini_key
   ```

4. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ```

> ⚠️ **Never commit `local.properties` or `google-services.json`** — ensure they are listed in `.gitignore`.

---

## 📋 Handover Notes & Technical Debt

For developers taking over or contributing to this project, be aware of the following architectural decisions and technical debt:

1. **ContentHolder Singleton:** Large web page extracts are currently passed between `MainActivity` and `ActivitySummary` via a static singleton (`ContentHolder`). This is vulnerable to process death. *Recommendation: Migrate to a file-based cache transfer.*
2. **Room Migrations:** The app currently uses `fallbackToDestructiveMigrationOnDowngrade()`. If the database schema changes in the future, proper `Migration` objects must be written to prevent user data loss.
3. **RecyclerView Adapters:** Adapters currently use `notifyDataSetChanged()`. *Recommendation: Migrate to `ListAdapter` with `DiffUtil` to improve scrolling performance and enable animations.*
4. **API Key Storage:** Custom API keys added by the user are stored in a local JSON file (`custom_ai_apis.json`). *Recommendation: Wrap this in `EncryptedSharedPreferences` via `androidx.security:security-crypto`.*
5. **WebView Security:** `usesCleartextTraffic="true"` is enabled in the Manifest for local development/testing. For production releases, this should be restricted to `https` only.

---

## 📊 Project Metrics

| Metric | Value |
|---|---|
| **Lines of Code** | ~12,000+ (Kotlin + XML) |
| **Activities** | 12 |
| **Background Services** | 1 (Audiobook TTS) |
| **Database Tables** | 5 (Room) |
| **Supported AI Models** | 3 Built-in + Unlimited Custom |
| **Min SDK** | API 28 (Android 9) |
| **Target SDK** | API 36 (Android 14) |

---

## 🔮 Future Improvements

- [ ] **Word-Level Highlighting:** Highlight the exact word being spoken in the Audiobook UI using `SpannableString` and TTS `onRangeStart`.
- [ ] **DiffUtil Integration:** Upgrade all RecyclerViews to use `ListAdapter`.
- [ ] **Export/Import Backup:** Add a feature to export the Room database and Audiobook files to a local `.zip` backup, independent of Firebase.
- [ ] **Batch Summarisation:** Queue multiple chapters for sequential summarisation.
- [ ] **EPUB/PDF Export:** Compile saved novels into standard ebook formats.

---

## 🔒 Privacy & Security

- **Local-First:** All reading history, bookmarks, and summaries are stored locally on the device by default.
- **Opt-In Cloud Sync:** Firebase sync is strictly disabled by default and requires explicit user authentication and opt-in.
- **No Telemetry:** The app does not track user reading habits or send analytics data to third parties.
- **Direct API Calls:** AI summarisation requests are sent directly from the device to the AI provider. No content is routed through intermediate proxy servers.

---

## 👤 Author

**Sudipta Roy**  
Android Developer | Java & Kotlin  
📧 sudiptoroy7666@gmail.com  
🔗 [Portfolio](https://sudiptoroy7666-lgtm.github.io/portfolio/) · [LinkedIn](https://www.linkedin.com/in/sudipta-roy-3873512b4/) · [GitHub](https://github.com/sudiptoroy7666-lgtm)
```
