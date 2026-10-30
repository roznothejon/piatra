# How Piatra decides what to do

Every routing rule, correction and time/text rule between "you spoke" and "something happened", in one place. **Keep this file current:** when a rule changes in code, change it here in the same commit. To check a change, re-run the test set in the app's **Agent lab** (`docs/agent-eval.md`).

Each rule names where it lives (paths under `app/src/main/kotlin/dev/piatra/`). Most of them came from real voice notes that went wrong; where it helps, the rule says what the model did before it existed.

## 1. The pipeline, in order

1. **Transcribe** (Parakeet, on the phone). A transcript without speech fails with "No speech heard": it needs at least two letters or digits once sound markers like `[noise]` or `(music)` are removed. `core/SpeechText.kt`
2. **Shortcut rules** (section 2). If one matches, it decides alone and the agent model isn't run. 📏
3. **The agent model** (`needle-pebble-ft`) picks tools from the five it was trained on: `create_note`, `create_reminder`, `create_list_item`, `set_alarm`, `set_timer`. `create_list` is never offered to it. 🤖
4. **Corrections** of known model mistakes (section 3). 🔧
5. **The tools run**, each with its own rules for text and time (sections 4–6).

The grey line under each recording shows every call and who decided it: 🤖 model, 📏 rule, 🔧 correction. The recording's `agentResult` column stores the same thing.

**One recording never creates the same item twice:** a repeated note, or the same list item (ignoring capitals) on the same list with the same time, is skipped. A safety net for when rules and corrections meet several model calls. `core/agent/NoRepeatsItemStore.kt`

Running a recording again (Transcribe again, or a retry after a crash) first deletes what it created last time and cancels those reminders' alarms. **Exception:** alarms and timers already handed to the Clock app stay there, so running again can set them twice. `core/agent/Agent.kt` (`AgentRunner`)

## 2. Shortcut rules (keywords)

Matched only **at the very start** of the transcript, ignoring capitals, as whole words ("note" doesn't match "notebook"), skipping punctuation after the phrase ("To do, call mom." → "call mom"). Longer phrases are tried first ("make a note" before "note"), across all rules. If nothing follows the phrase ("To do."), no rule matches and the model decides. `core/agent/ShortcutRules.kt`

**Editable:** Settings → Shortcut phrases (stored in the `shortcut_rules` table). Each rule has phrases, what it does (save a note / add to a chosen list / make a new list), **Keep the phrase in the text** (off: "To do, call mom" saves "call mom"; on: "Call the dentist" saves "Call the dentist"), and an on/off switch. Switched-off rules never match. A phrase can belong to only one rule. A list rule points at its list by id, so renaming the list keeps it working; deleting the list deletes its rules. The table below is the **defaults**: put in on first use, and brought back by **Reset**. The agent lab uses your own rules and lists for typed phrases, but always the defaults for the test set.

| You say (defaults) | Becomes |
|---|---|
| **to do** / **to-do** / **todo** / **too do** (a mishearing) … | item on **Todo** |
| **remember to** … | item on **Todo** (the model read it as a note) |
| **make a note that** / **note down that** / **note that** / **note to self** / **make a note** / **note down** / **note** … | **note** of what follows ("Note to self: X" saves X) |
| **buy** … | item on **Shopping** |
| **create a new list called** / **create a new list** … | a **new list** with that name |

A time in the rest of the sentence becomes the item's reminder time (section 5): "To do call mom tomorrow at nine am" → ☐ Call mom · Todo · 🔔 tomorrow 09:00.

To change the defaults: `defaultShortcutRules` in `core/agent/ShortcutRules.kt` (only affects new installs and Reset).

## 3. Corrections after the model

| The model chose | And | Becomes | Why |
|---|---|---|---|
| `create_list_item` | the transcript says "remind" or "reminder", **and the model made only this one call** | `create_reminder` with the same text | The model sends reminders without a time to the shopping list. Several list items mean the model split a list: "Remind me to buy milk, eggs and butter" stays ☐ Milk, ☐ Eggs, ☐ Butter on Shopping (it made three identical Todo items before) |
| one `create_list_item` | its item is (nearly) the whole sentence (at most one word left over), and nothing in the transcript points to a list: "list", "buy", "get", "need", "add", "shopping", "grocery"/"groceries", "todo", or a word of one of your list names | `create_note` with the whole transcript | Not a request: "Thank you" became a shopping item. Such voice notes are kept as notes |
| `create_list_item` | the transcript names "**<word> list**" and you have no such list ("my packing list") | the same call with `list_name` set to that word, so it lands on **Todo** (section 7) | The model writes "shopping" for a list it doesn't know |
| `create_list_item` | the transcript names "**<word> list**", you have that list, and the model chose another ("add puka to my cat list" with a Cats list → "shopping") | the same call with `list_name` set to that list's title, so it lands there | The model guesses "shopping" even for lists that exist |
| nothing | | `create_note` with the whole transcript | Same reason as "not a request" |

`core/agent/ShortcutsFirstAgent.kt`

## 4. Which text gets saved

| Tool | Text saved |
|---|---|
| `create_note` | The model's text **only if it's word for word a piece of what you said** (ignoring capitals and punctuation, whole words); otherwise your **whole transcript**. The model garbled long sentences. The model writes numbers as digits ("thirty" → "30"), which then don't count as word for word, so those notes use the transcript. **A single word out of a longer sentence doesn't count either** ("Remind me tomorrow that it's gonna rain" came back as "rain"). `core/agent/Tool.kt` (`modelTextOrTranscript`) |
| `create_reminder` | The same rule. Then, if the model's own time field was used, that time phrase is cut out ("…cake **in 30 seconds** so I…" → "…cake so I…"). A time Piatra found itself (a guess) leaves the text as said, because the phrase may be part of what to remember ("tell Ana the bus leaves at 5"). When the **transcript** is saved and the model's time was used, that time is also cut from the transcript in your own words, if it's the same moment, and any other time stays ("Remind me to schedule a meeting for Friday at eight **tomorrow at nine am**" → "Schedule a meeting for Friday at eight", rings tomorrow 9:00). A saved transcript also loses a leading "(please) remind me (to/that/about/of)" ("Remind me that the meeting…" → "The meeting…"). The model's own text never contains that request. `core/time/ReminderTime.kt` (`restAsSaid`) |
| `create_list_item` | The model's item as is (short and usually right, and "Milk, eggs, butter." arrives as one call per item), without a final full stop. A time found inside it is cut out (section 5). |
| `create_list` | The name, capitalised. A name that already exists (ignoring capitals) doesn't create a second list. |
| `set_alarm` | The model's label, if any. |

Every saved text starts with a capital letter. Empty text from the model → the transcript.

**Settings → "Use the model's wording"** (on by default). Off: `create_note` and `create_reminder` always save the transcript (for reminders, still minus the time that rings and a leading "remind me (to)"), and a time is never read out of the model's message; the model's time fields still count. Unchanged when off: text from a shortcut rule (it's ours, not the model's), list items (so the model still splits "milk, eggs and butter"). Corrections act on the model's calls, so its wording follows the switch. The agent lab's typed phrases follow the switch; the test set always runs with it on. `Settings.useModelText`, passed to the tools via `piatraTools(useModelText = …)`; `ToolContext.decidedBy` tells a rule apart.

## 5. Where the time comes from

| Tool | Order tried |
|---|---|
| `create_reminder` | 0. a time said **right after "remind me"**, before "to/that/about/of" ("Remind me **tomorrow at nine** to schedule a meeting for Friday at eight"): it's always the when, so it beats the model, which garbled or merged such times; the saved text is then what follows, unless the model's text passes the word-for-word check. A bare hour there follows section 6: "tomorrow at nine" is 09:00, even when the model sent "9pm"; 1. the model's `date_time_human` or `duration_human`, if readable; 2. a time phrase in the model's message (often written more tidily, and with two times in a sentence it tends to keep the right one), **only if the transcript has a time too**: the model makes times up ("…reminder: 3pm" when no time was said); 3. one in the transcript; 4. none. A time the model made up that can't be read ("in 1pm") is dropped. `notification_hours_before` is ignored (no early heads-up). A model time that cuts "the day after tomorrow" short ("tomorrow") is ignored, so step 2 finds the full phrase. `core/agent/CreateReminderTool.kt` |
| `create_list_item` | 1. the model's `reminder_date_time_human`, if readable; 2. a time phrase inside the item text, then cut out of it; 3. none. `core/agent/CreateListItemTool.kt` |
| `set_alarm` | 1. **your words** (the transcript), if the parser can read a time: first with "for" read as "at" ("an alarm **for** 7:30"), except before a duration ("for ten minutes"), then as said. The "for → at" reading goes first because in "an alarm for six AM tomorrow" the words as said give only "tomorrow" (it used to ring at 9:00); **but if your words only name a day** ("tomorrow", "on Friday", "the day after tomorrow"), with no time of day, **the model's hour and minute on that day** win over the day's default 9:00: for an alarm, a day alone means the parser missed the time ("tomorrow at sunrise", model 6:15 → tomorrow 06:15). The model's hour is taken as given (24-hour) there; if it sent none, or that moment has passed, the day's 9:00 stays; 2. the model's `time_hours`/`time_minutes`; 3. otherwise it fails. Your words win because the model read "in one minute" as 1:00 and "two twenty seven" as 2:20 (and "two thirty one" as 14:30). `core/agent/SetAlarmTool.kt` |
| `set_timer` | 1. the model's `time_human` ("10 minutes", also tried as "in 10 minutes"); 2. a time in the transcript; 3. otherwise it fails. `core/agent/SetTimerTool.kt` |

## 6. How times are read

All in `core/time/ReminderTime.kt`, on top of upstream's parser (`core/time/HumanDateTimeParser.kt`).

- **Counted from when you spoke**, not from when Piatra processed the recording: "in 2 hours", "today" and "tomorrow" are relative to the recording's start time.
- **A bare hour (1–12, no am/pm) means whichever of am and pm comes first** after you spoke, whatever the phone's 12/24-hour setting.
  - "two twenty seven" said at 14:26 → **14:27**
  - "at 3" said at 13:30 → **15:00**
  - "seven" said at 23:00 → **07:00** tomorrow
  - "at 12" said at 10:00 → **12:00** (noon)
- **A bare hour on another day** ("on Friday at three", "tomorrow at nine") means **waking hours**: 1–6 is the afternoon, 7–12 the morning or noon. "On Friday at three" → Friday 15:00, "tomorrow at nine" → 09:00, "next Monday at seven" → 07:00. On the day it was said ("today at nine"), the rule above applies. A part of the day settles it ("tomorrow morning at 3" → 03:00). `core/time/ReminderTime.kt` (`onADay`)
- **"Wake me up …"** (alarms): a bare hour means the **morning** one: "wake me up at six" said at 14:00 → 06:00 tomorrow, not 18:00. Durations ("wake me up in two hours") are unaffected. `core/agent/SetAlarmTool.kt`
- **Taken exactly:** an explicit am/pm ("at 3am") or part of the day ("in the morning"), an hour of 0 or 13–23 ("15:00"), or a leading zero ("02:27").
- **A time of day that has passed** means the next time the clock shows it (tomorrow).
- **A date without a time** ("on Friday", "the 21st of September") means **09:00** that day.
- **"In N minutes/hours/days"** is an exact duration: "in 1 day" is exactly 24 hours, so across a daylight-saving change the clock time shifts by an hour. **"In N months/years"** moves the calendar date and keeps the clock time.
- **Day-first dates** are understood: "the 21st of September", "21 September", "the twenty-first of September" (a Piatra addition; upstream only read "September 21st"). "May" is also a verb, so "2 may" is only a date with "the" or "of" ("the 2nd of May").
- **"The day after tomorrow"** is two days on; inside a sentence it wins over "tomorrow" (a Piatra addition).
- **"An hour and a half"**, "1 hour and a half", "two and a half hours", "a minute and a half" add the half (a Piatra addition; upstream read the first as 1 hour).
- **"… next year"** after a date means that date in the year after this one: "the 21st of September next year" → 21 Sep 2027 (said in 2026).
- Also understood (upstream): word numbers ("two twenty seven", "in thirty seconds"), "noon", "midnight", "half past seven", "quarter to eight", "in the morning/evening", "tomorrow", weekdays ("next Monday"), "in a couple of hours".
- A reminder whose time has already passed when it's saved fires right away.

## 7. Lists

`core/agent/Lists.kt`

- Built in: **Todo** and **Shopping**, found by a fixed keyword (`seed`) even after renaming.
- **Every reminder is an item on Todo**; one with a time also rings.
- The model's list name is matched against your lists: exact title, then a title starting with it, then a word in a title starting with it, then a title inside a longer name ("my grocery list"). A leading or trailing "list" is ignored.
- Synonyms: groceries / grocery → Shopping; to do / to-do / tasks → Todo.
- **An unknown name goes on Todo**, never creating a list from a possibly misheard word. By voice, lists are only made by the "create a new list" rule ("+ New list" on the Lists tab also makes one).
- **Renaming** (list screen, ⋮) changes the title the agent matches: after renaming Shopping to Groceries, "add milk to groceries" finds it, and "buy milk" still does through its keyword. Todo and Shopping can't be deleted (the agent would make them again).

## 8. Alarms, timers and notifications

- **Reminders** ring by themselves: an exact alarm (`setAlarmClock`) posts a "Reminder" notification with a **Done** button, even with Piatra closed. Alarms are set again after a restart, an app update, and at every app start. Ticking an item off cancels its alarm; unticking sets it again if it's still to come. `android/AndroidReminderScheduler.kt`, `ReminderReceiver.kt`, `BootReceiver.kt`
- **Alarms and timers** are handed to the phone's **Clock app**. The Clock app takes only hour and minute, so an alarm is for the next time the clock shows it. `android/AndroidClockApp.kt`
- **A timer ends when it would have if set the moment you spoke**: the Clock app gets only what's left. One that would already have ended fails.
- **If Piatra isn't on screen**, Android won't let it open the Clock app, so a notification asks you to **tap** to set the alarm or timer. A timer set that way starts when tapped.

## 9. Known limits

- The agent model is small: it sometimes leaves the time out of its time field, invents one, or rewords text. The rules above catch the common cases; the 🤖 line shows what it actually sent.
- With **two times in one sentence** ("remind me that the meeting starts at nine at ten"), a guessed time may pick the wrong one. Safest phrasing: "Remind me **at/in/on [time]** to [thing]".
- **Other languages:** the multilingual speech model transcribes 25 European languages, but the agent model and the rules above only understand English.
- **A single word on its own** ("Bread.") with no list cue counts as "not a request" and becomes a note. Say "buy bread" or "bread on the shopping list".
- **Transcribe again** can set an alarm or timer in the Clock app a second time.
- If the model's rewording bothers you, switch off "Use the model's wording" (section 4) to always save what you said.
