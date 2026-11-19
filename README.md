# Muse Ear

Turn a spare phone into a Muse speaker. Parked on its charger, the phone listens for a
wake word; speak to it, and your Muse answers out loud through the phone's speaker.

**Status: early prototype (0.7).** This runs my house — one on my desk, one in the living
room — and is shared as a starting point, not a finished product.

## How it fits together

The phone is a Muse Gadget first (the bridge app + Termux setup from
`phone-as-muse-gadget`, with the companion bridge from `muse-bridge` running on the
phone). Muse Ear adds ears and a voice on top:

- **Wake word, on-device**: openWakeWord (ONNX) listens locally — nothing is streamed
  anywhere while it waits.
- **Gates**: a wake hit has to survive several checks before the phone starts listening
  for real (see below).
- **Recognition**: what you said is transcribed by the phone's speech recognizer
  (Google), or by a relay STT endpoint if you configure one.
- **To Muse**: the text is POSTed to the bridge on the same phone
  (`127.0.0.1:47821/ask`), which hands it to your paired Muse through the stock gadget
  SDK socket, in a dedicated chat.
- **The answer**: Muse replies through the gadget's speech command, and the reply is
  spoken through the phone's speaker in the voice your setup uses.

## Start with "Hey Jarvis"

Two wake-word models are bundled:

- `hey_jarvis` — the stock openWakeWord model. **Enabled by default; start here.**
  It is trained on English "hey jarvis", so say it in English.
- `hi_clio` — a custom model trained for my Muse's name ("하이 클리오", Korean). It is
  the worked example of the fun endgame: a wake word with your own Muse's name on it.
  Train yours, drop the `.onnx` into `assets`, and it is picked up by name prefix.
  Delete this one if it is not yours.

## The gates are the real project

A wake-word model alone false-triggers — on TV dialogue, on music, on words that merely
rhyme. Muse Ear layers checks after a hit, and most of its version history (see
`CHANGES.md`) is a diary of false alarms:

- the hit must hold for several consecutive audio chunks, not just spike once;
- a short spoken prompt ("말씀하세요~" — a bundled Korean sample; replace it with your
  own) covers the recognizer's start-up gap;
- a **relative silence gate**: the room right before the wake word must be quieter than
  the call itself;
- an **absolute energy veto** (0.7): if the audio right before the wake word is
  near-silent in absolute terms, the hit is discarded — that one killed the last class
  of "it woke up in an empty room" reports;
- a capture mode that saves the ~3 seconds before each hit, so a false wake can be
  diagnosed instead of guessed at. Recognized speech itself is never logged (lengths
  only).

## Settings

Relay URL (where recognized text goes), per-model wake thresholds, "hey jarvis" on/off,
the silence-gate level, hit-hold chunks, capture mode on/off, and which recognizer
service to use. The relay default still points at my old home setup — set your own
before first run.

## What you need

- An Android phone, Android 10+ (it runs on a Note 9 and an XCover5 here)
- The `phone-as-muse-gadget` setup on it, with the `muse-bridge` companion bridge
- A speech path for Muse's answers (the gadget TTS command your setup already uses)

## Known limitations

- The recognizer is the phone's system one, in your device language; quality varies
  by device and by room.
- Old phone microphones are not far-field smart-speaker arrays. Speak like you mean it.
- False wakes become rare with the gates, not impossible. Capture mode is your friend.
