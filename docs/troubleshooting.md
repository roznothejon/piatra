# Troubleshooting

Problems you may run into, roughly in the order you'd meet them. Each one says what you see,
why it happens and what to do.

**Two places to look first:**

- **In the app: ☰ → Logs.** Piatra's own log lines since it last started. **Warnings** shows
  only the problems. Copy them if you ask someone for help.
- **On the board** (with a laptop paired in dev mode): `tools/dev.py monitor` shows the last
  4 KB of the board's log, kept through restarts and crashes, then live lines. See
  [`../firmware/README.md`](../firmware/README.md).

## Models

**"Can't reach the download server. Check the internet connection, and that Piatra's
Network permission is on."** The download needs the internet once. Some Android versions
(GrapheneOS, for one) have a per-app Network permission: when it's off, Piatra is told it
may use the network but gets no connection. Turn it on for the download (Settings → Apps →
Piatra → Permissions), then off again if you like.

**The download stops or fails halfway.** Keep Piatra open while it downloads, and check
there's about 500 MB free. Tap **Resume** or **Retry**: it continues where it stopped.
"The download was damaged … Try again" means the file arrived broken; retrying downloads it
again.

**"This phone's CPU can't run Cactus, so on-device models won't work."** The engine needs a
64-bit ARM CPU with the ASIMDHP and ASIMDDP features (most phones from 2018 on). Older phones
and x86 emulators can't run it. Recording, lists and notes still work; transcription doesn't.

## Recording and transcription

**"Too short" (phone mic) or nothing at all (board).** Recordings under a second are
dropped; on the board, such a press counts as a tap. Hold the button, wait for the buzz, then
speak.

**"No speech heard".** The transcript had no words in it: the recording was silent, noise,
or very quiet. Speak within about 20–30 cm of the board's microphone.

**The transcript is poor.** Check that the speech model matches your language (Settings →
Speech model): English is the most accurate for English. Then **Transcribe again** on the
recording.

**"Speech model not downloaded yet".** Download it in Settings. The recording waits and is
transcribed as soon as the model is ready.

**A recording stays on "Gave up after N attempts" or another failure.** Open it and tap
**Try again**. If it keeps failing, the log (☰ → Logs) says why.

## The agent did the wrong thing

- Open the recording and read the grey line: 🤖 is what the model chose, 📏 a shortcut
  phrase, 🔧 a correction. That shows *who* got it wrong.
- **Rephrase:** "Remind me **at/in/on [time]** to [thing]" is the most reliable form for
  reminders. Avoid two times in one sentence.
- **A word the agent keeps getting wrong at the start of a sentence?** Add it as a shortcut
  phrase (Settings → Shortcut phrases), which decides without the model.
- **The text was reworded in a way you don't like?** Turn off Settings → **Use the model's
  wording**, so notes and reminders save exactly what you said.
- **Try it without recording:** Settings → Agent lab, type the phrase. Nothing is saved.
- Fix it by hand afterwards: tap the item to edit its text or time, or **Transcribe again**
  after changing a setting.

## Reminders, alarms and timers

**A reminder didn't ring.** Check that Piatra may send notifications (the Recordings tab
warns if not). Some phones' battery savers stop apps' alarms: set Piatra's battery use to
"Unrestricted" in Android's app settings. A reminder that came due while the phone was off
rings as soon as it's back on, and one whose time had already passed when it was saved rings
at once.

**An alarm or timer asks me to tap a notification.** Android doesn't let an app open
another app (the Clock app) from the background, which is where board recordings are
handled. Tap the notification to set it. A timer started that way runs from the tap.

**"No Clock app on this phone accepts alarms and timers from other apps".** Install or
enable a Clock app that handles Android's standard alarm and timer requests (Google's Clock
does).

**An alarm was set twice.** **Transcribe again** removes what a recording made in Piatra,
but alarms and timers already in the Clock app stay there. Delete the extra one in the Clock
app.

## Pairing

**"Pairing needs Android 13 or newer".** Pairing uses Android's companion-device dialog in a
form that arrived in Android 13.

**"Piatra needs the 'Nearby devices' permission".** Allow it when asked, or in Android's app
settings (Permissions → Nearby devices). Piatra uses it to connect to the board and to scan
for its adverts; it's declared as not used for location.

**"No board was paired" / "The board turned down pairing".** The board accepts a new phone
only when it has no pairing yet, or for a minute after **exactly 5 taps** (long buzz, then
the light blinks slowly). Tap 5 times, then **Pair** within the minute.

**"Couldn't talk to the board … forget it here and in Bluetooth settings, then pair
again."** The board and the phone no longer agree on their pairing, for example because the
board paired with another device since (each new "Just Works" pairing, including a laptop
running `sync.py`, replaces the old one). Tap **Forget** on the Device screen, remove "Piatra"
in Android's Bluetooth settings, then tap the board 5 times and pair again.

## Sync

**Recordings don't sync by themselves.** Go through these in order:

1. **Is Bluetooth on**, and is the phone within a few metres? Range through a metal case is
   about 5 m, less with the board's antenna side facing away.
2. **The Device screen says "Automatic sync is off: Android forgot that Piatra and the board
   belong together."** Android only lets Piatra start a sync from the background while that
   link (the companion-device association) exists, and it sometimes disappears. Tap the
   board's button once, press **Restore**, and pick the board in Android's dialog.
3. **A notification says "Automatic sync was refused".** Android didn't allow a background
   start this time. Open Piatra and press **Sync**.
4. **Is the board awake?** It advertises only when it has recordings waiting (for two minutes
   after each one), after a tap, and briefly every 30 minutes. Tap it once and watch **Last
   seen** on the Device screen update.
5. **Is a laptop connected to it** in dev mode? The board takes one connection at a time.

The board keeps every recording until the phone has saved it, so a sync that didn't happen
only means waiting. Nothing is lost.

**"Couldn't reach the board. Tap its button to wake it, then try again."** It was asleep or
out of range.

**"The board's firmware speaks protocol vN; update the app or the firmware."** The app and
the firmware are from versions that don't match. Update the older one.

**A recording shows up twice.** It shouldn't: Piatra recognises a recording it already has
and only deletes it on the board. If you see one, the log has the details.

## The board

**The battery reading is "Not measured".** The board needs a voltage divider from the
battery to D0 to measure it (see [`../README.md`](../README.md#hardware)). Without one, the
pin reads noise, and readings under 3.2 V are treated as "not measured".

**It drains faster than expected.** Check the Device screen's **Battery history**. With a
laptop: `dev.py pm` shows how much time the board spent awake and what kept it awake, and
`dev.py adv` why it's advertising. A board that stays awake (dev mode, a phone that keeps
connecting) uses many times more power than one that sleeps.

**Range is very short (about a metre).** A metal case costs 10–25 dB. Beyond that, check the
antenna connection: a badly soldered or loose antenna connector can lose another 25–45 dB.
`tools/range.py` shows the board's signal at a laptop every 2 s, which makes a bad
connection easy to spot. Phones that support Bluetooth's long-range mode (Coded PHY) reach
further; Piatra uses it automatically when the phone has it.

**Clicks or crackle in recordings.** The board's radio transmits at +20 dBm, and each
transmission can click in the microphone. The firmware stops advertising while recording
and asks a connected phone to talk to it less often, so recordings should be clean apart
from up to ~0.2 s at the start while the phone agrees. If you still hear regular clicks,
a large capacitor (e.g. 470 µF) across the battery pads helps.

**"The board restarted" notifications.** The board reports why it last restarted: a
**brownout** (the battery sagged too low: charge it, or check the battery connection), a
**watchdog** (the firmware hung) or a **crash**. A recording being made at that moment may
be lost. Repeated crashes are a firmware bug: `dev.py log` shows the lines before it.

**"Board storage low" / "Board storage full".** Sync it: storage frees up as the phone takes
the recordings.

## Firmware updates

**`dev.py` can't connect ("Page Timeout", `le-connection-abort-by-local`).** Close GNOME
Settings' Bluetooth panel: while it's open it keeps scanning, and BlueZ can't connect.
Turn the phone's Bluetooth off too, or it may grab the board's only connection first.

**The new firmware "disappeared" after flashing.** It didn't pass its trial: it crashed, or
it wasn't confirmed within 5 minutes, or it didn't come back after a deep sleep, so the
board went back to the previous firmware. `dev.py info` shows which one is running, and
`dev.py log` what happened.

**The board doesn't respond to `dev.py` at all.** The laptop's pairing survives, but the
board must be awake: tap it once, or run `dev.py wait` for its next 30-minute check-in. If
the laptop's pairing was lost, open dev mode again with 8 taps and a 2 s hold, then
`dev.py pair`.

**A bootloader update was interrupted and the board won't start.** Flash it over USB
(`idf.py flash`). This is the only update that can leave the board unable to start.
