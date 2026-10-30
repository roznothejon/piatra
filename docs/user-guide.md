# User guide

How to set up and use Piatra once the app is installed and the board is built and flashed
(see [`building.md`](building.md) and [`../firmware/README.md`](../firmware/README.md)).
Things not working? See [`troubleshooting.md`](troubleshooting.md).

## First start

1. **Download the models.** The Recordings tab shows "Download the models to transcribe
   recordings." with a **Set up** button, which opens Settings. Under **Speech model**, pick
   English or Multilingual and tap **Download** (about 400 MB). Do the same for the
   **Agent model** (about 14 MB). Use Wi-Fi, and keep Piatra open while it downloads; you can
   **Pause** and **Resume**. After this, Piatra never needs the network again.
   - **English** is the most accurate for English. **Multilingual** transcribes 25 European
     languages, but the agent only understands English, so in other languages you get a
     transcript and a note rather than reminders.
2. **Allow notifications** when Android asks. Without them reminders can't ring, and the
   Recordings tab says so with an **Open settings** button.
3. **Try it with the phone's microphone:** hold the round **Hold to record** button on the
   Recordings tab, say "Remind me to call mom in two minutes", and let go. Android asks for
   the microphone the first time. A few seconds later the recording shows its transcript and
   what the agent made: a reminder on the Todo list that rings in two minutes.

## Pairing the board

1. Open **☰ → Device** and tap **Pair**. Android asks for the **Nearby devices** permission
   (Bluetooth), then shows its own "Pair with Piatra?" dialog.
2. A new board pairs straight away. If the board was paired with another phone before, tap
   its button **5 times** first: a long buzz, then the light blinks slowly, and for a minute
   it accepts a new phone. Then tap **Pair**.

Pairing needs Android 13 or newer. The board pairs with one phone at a time.

The Device screen then shows the board's **Battery**, **Storage**, **Recordings waiting**,
**Firmware**, **Last seen** and any **Problems**, plus a **Battery history** trend.
**Check now** reads the latest status, **Sync now** syncs, and **Forget** unpairs it.

## Recording with the board

- **Hold the button**, wait for the short buzz, speak, and let go. A recording can be up to
  5 minutes long. The board holds about 40 minutes in total.
- **A press shorter than a second is a tap**, not a recording. A tap makes the board ask the
  phone to check in, which updates its battery and storage on the Device screen.
- **Syncing is automatic.** After each recording the board advertises for two minutes. When
  the phone is within a few metres, Piatra connects in the background, downloads the
  recording, and the board deletes its copy. A notification shows while it syncs. The board
  also checks in every 30 minutes, so anything missed syncs later.
- **To sync right away**, tap **Sync** on the Recordings tab (shown once a board is paired)
  or **Sync now** on the Device screen. If the board is asleep, tap its button first.

## What to say

The agent understands everyday English. Some phrasings work every time:

| Say | You get |
|---|---|
| "Remind me **at 5pm** to call the dentist" | ☐ Call the dentist · Todo · rings at 17:00 |
| "Remind me **tomorrow at nine** to pay the rent" | ☐ Pay the rent · rings tomorrow 09:00 |
| "Remind me **in twenty minutes** to check the oven" | rings in 20 minutes |
| "**To do** book the car service" | ☐ Book the car service · Todo |
| "**Remember to** water the plants" | ☐ Water the plants · Todo |
| "**Buy** oranges" / "Milk, eggs, butter." | items on Shopping |
| "Add sunscreen to my **packing list**" | on your Packing list (or Todo, if you have none) |
| "**Create a new list called** packing" | a new list |
| "**Note** the gate code is 4512" / "**Note to self:** …" | a note |
| "**Set an alarm for** seven thirty" / "**Wake me up at** six" | an alarm in the Clock app |
| "**Set a timer for** ten minutes" | a timer in the Clock app |

Anything that isn't a request ("Thank you", a thought you want to keep) is saved as a
**note**, so nothing you say is lost.

**How times are read:**

- Times count from when you **spoke**, not from when the phone received the recording.
- A bare hour means the next time the clock shows it: "at 3" said at 13:30 is 15:00, "seven"
  said at 23:00 is 07:00 tomorrow. On another day, it means waking hours: "on Friday at
  three" is 15:00, "tomorrow at nine" is 09:00. "Wake me up at six" is always the morning.
- A day without a time ("on Friday") means 09:00.
- "In an hour and a half", "the day after tomorrow", "the 21st of September" all work.

**Safest phrasing for reminders:** "Remind me **at/in/on [time]** to [thing]". With two
times in one sentence, the agent may pick the wrong one.

The full rules are in [`agent-rules.md`](agent-rules.md).

## The app

**Recordings** (the first tab): every recording, newest first, with its transcript and what
it made underneath. Tap one to open it:

- Play it back, read the transcript, and see each created item.
- A grey line shows every decision and who made it: 🤖 the agent model, 📏 a shortcut phrase,
  🔧 a correction of a known model mistake.
- **Transcribe again** removes what the recording made and runs it again (useful after
  switching the speech model or changing shortcut phrases). Alarms and timers already in the
  Clock app stay there, so they may be set twice.
- **Delete** asks whether to keep the items it made.

**Lists:** Todo and Shopping are built in; **New list** adds your own. In a list, **Add
item** to type one, tap an item to edit it or **Add a reminder time**, tick it off when done.
The list menu has **Rename** and **Delete list** (Todo and Shopping can be renamed but not
deleted). A reminder is a Todo item with a time.

**Notes:** every note, spoken or typed (**New note**). Each spoken note links back to its
recording (**From recording**).

**☰ → Logs** shows the app's own log lines, with a **Warnings** filter, and can copy them,
which helps when something goes wrong.

## Reminders

A reminder rings at its time with a notification that has **Snooze** and **Done**. **Done**
ticks it off. With **Keep reminders until done** on (Settings → Reminders), it rings once and
then stays in your notifications until you tick it off; swiping it away only hides it until
it quietly comes back. **Snooze for (minutes)** sets the snooze length (10 by default).

Reminders ring with Piatra closed and are set again after the phone restarts.

## Alarms and timers

These go to the phone's Clock app, so they show and ring there. When Piatra isn't on screen
(as with a board recording synced in the background), Android won't let it open the Clock
app, so Piatra posts a notification instead: tap it to set the alarm or start the timer. A
timer started that way runs from the tap.

## Settings

- **Speech model / Agent model:** download, pause and choose (see First start).
- **Shortcut phrases:** the phrases that decide without the model ("To do …", "Buy …",
  "Note …"). Each one can add to a list, save a note or make a new list, keep or drop the
  phrase in the saved text, and be switched off. Reset brings back the defaults.
- **Use the model's wording:** on, notes and reminders keep the agent's tidier text when it's
  word for word what you said; off, they always save exactly what you said.
- **Also send to:** **Send to Tasker** passes notes and list items on to Tasker
  ([`tasker.md`](tasker.md)); **Send to Obsidian** writes notes as Markdown into a vault
  folder you choose, one file per note or all into `Piatra.md`.
- **Reminders:** Keep reminders until done, Snooze for (minutes).
- **Appearance:** light, dark or follow the system; wallpaper colours or Piatra's own; the
  font.
- **Backup:** see below.
- **Developer → Agent lab:** type a phrase to see what the agent would do, without saving
  anything, or run the test set ([`agent-eval.md`](agent-eval.md)).

## Backup and restore

**Settings → Backup → Export everything** saves one zip wherever you choose: every recording
(as WAV), transcripts, notes and lists as Markdown, and a backup file with everything
including settings and shortcut phrases. The Markdown and WAV files are readable without
Piatra.

**Restore from a backup** replaces everything on this phone with the backup's contents, after
showing what's in it and asking. It can't be undone, so export first if you might want
today's data back.

Not included, because they belong to the phone: the models (download them again), the
paired board (pair again) and the Obsidian folder (choose it again). Make a backup before
uninstalling Piatra or moving to another phone: uninstalling deletes all its data.

## Battery and charging

The board lasts about three weeks per charge with daily use (a 200 mAh cell). Piatra warns
with a notification when its battery is under 20 % or its storage under 15 % free, and the
Device screen shows the trend. Charge it through its USB port.

## Privacy

Recordings, transcripts and everything the agent makes stay on the phone, in Piatra's private
storage. Piatra has no account, no cloud service and no analytics. It uses the network only
to download the two models from Hugging Face; after that you can take the network permission
away (on phones that offer one) and everything keeps working. The link to the board is
encrypted, and only the paired phone can read it. Nothing leaves the phone unless you switch
on Tasker or Obsidian, or export a backup.
