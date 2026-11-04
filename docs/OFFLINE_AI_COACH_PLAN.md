# Offline AI Coach implementation plan

## Verified current architecture
- Android Java Activity hosts a local HTML/JavaScript app in WebView.
- Workout history is stored by the web app in LocalStorage.
- Existing coach logic computes set tempo, rest intervals, previous-session comparisons, exercise cues, and day/week reviews.
- The Android bridge currently exposes share/open only; no GGUF inference runtime is present.

## Implementation phases
1. Preserve existing logging and formalize a versioned JSON analytics payload: exercise, muscle group, completed sets (weight, reps, whole-set elapsed seconds, timestamp), prior sessions, goal, and rest intervals.
2. Add Android Storage Access Framework selection for .gguf, copy into app-private storage, and expose model status/remove/reselect to WebView.
3. Integrate a pinned llama.cpp Android NDK/JNI build for arm64-v8a. Verify model load, memory settings, cancellation, and low-memory behavior on a real device.
4. Keep numeric conclusions deterministic. Use Qwen for Korean explanations from validated facts and exercise cues. Validate structured output; fall back to the existing rule-based coach.

## Safety and recommendation rules
- Whole-set elapsed time is not rep velocity and cannot prove form quality or proximity to failure.
- Do not diagnose injuries. Pain, dizziness, or unusual symptoms should trigger stop-and-assess advice.
- Do not recommend load increases from one isolated set; consider repeated sessions, target rep range, completed sets, and user-reported effort/RIR when available.
- Keep workout records, model, and inference on-device; no external AI API.

## Build acceptance criteria
- Existing logging, history, routines, diet, and statistics remain functional.
- A GGUF file can be selected and model status survives app restart.
- llama.cpp loads a GGUF and generates Korean text with airplane mode enabled on an arm64 Android device.
- The coach separates deterministic comparisons from validated Qwen commentary.
- GitHub Actions produces a debug APK that installs and passes a real-device smoke test.