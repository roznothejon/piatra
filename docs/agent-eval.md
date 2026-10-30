# Agent evaluation

How good is the on-device agent model (`needle-pebble-ft` v2.0.1), and how much do Piatra's
rules add? The **Agent lab** answers that with a fixed test set. Run it after every change
to the rules in [`agent-rules.md`](agent-rules.md).

## Running it

1. In the app: Settings → **Agent lab** → **Run test set**. It takes about a minute and needs
   the agent model downloaded.
2. The summary shows on screen. The full report, phrase by phrase, is saved on the phone.
   With a debug build you can pull it:

   ```sh
   adb exec-out run-as dev.piatra cat files/eval/agent-eval-latest.md
   ```

The lab also lets you type any phrase and see what would happen. Everything in the lab is a
**dry run**: nothing is saved, no alarm is set and nothing goes to Tasker or Obsidian. Every
phrase is treated as said on **Thu 22 Oct 2026, 14:00** (`ANCHOR`), so expected times can be
written down, and runs are repeatable because the model is deterministic. Typed phrases use
your own shortcut rules and lists; the test set always uses the defaults.

### From a laptop (debug builds)

Debug builds include a broadcast receiver (`app/src/debug/kotlin/dev/piatra/debug/LabHookReceiver.kt`)
that runs the lab over adb, so you can check a change without touching the phone:

```sh
adb shell am broadcast -p dev.piatra -a dev.piatra.debug.TRY --es phrase "add puka to my cat list"
adb shell am start -n dev.piatra/.MainActivity      # the test set needs the app in front
adb shell am broadcast -p dev.piatra -a dev.piatra.debug.TEST_SET
adb logcat -s PiatraLab                              # decisions, created items and the score
```

Add `--ez model_text false` to `TRY` to override the "Use the model's wording" setting.

## The test set

40 English phrases in `app/src/main/kotlin/dev/piatra/core/eval/EvalCases.kt`, in six
groups: reminders, lists, "to do", notes, alarms and timers, and things that aren't requests
("Thank you."). Each case says which tools the model alone may pick and what should exist
afterwards. **The expectations describe what a person would mean, not what Piatra does
today**, so a failing case is a known weakness, not a mistake in the test.

To add a case, add an `EvalCase` to `EVAL_CASES` using the helpers at the top of the file
(`todo`, `onList`, `note`, `alarm`, `timer`, `newList`, and `day(…)` for times). A new rule
should come with at least one case that fails without it.

**Scoring** (`core/eval/EvalRunner.kt`):

- **Model:** the agent model alone picked only acceptable tools (for "to do …", a reminder
  and a Todo item are both fine). It's asked even when a shortcut rule decides, so the model
  is always scored. Cases whose tool the model isn't offered ("create a new list") aren't
  scored here.
- **Result:** after rules → model → corrections → tools, exactly the expected things exist:
  the right kind, on the right list, containing the key words (and none of the forbidden
  ones), at the right time (within a minute). Nothing extra.

## Results

At the last recorded run, the model alone picked acceptable tools in **32 of 39** scored
cases, and the full pipeline got **40 of 40** right:

| Group | Model | Result |
|---|---|---|
| Reminders | 11/14 | 14/14 |
| Lists | 7/8 | 9/9 |
| Todo | 2/3 | 3/3 |
| Notes | 5/5 | 5/5 |
| Alarms & timers | 7/7 | 7/7 |
| Not requests | 0/2 | 2/2 |

Before most of the rules and corrections existed, the pipeline got 32 of the 40 right. The
model was the same in both runs: every gain came from rules, corrections and the time parser.
If a run scores lower than the table, a recent change broke something.

## What we learned about the model

**It's a good router for clear English commands.** Reminders with a time at the start or end,
weekdays, dates, list items (including "Milk, eggs, butter." as three items), notes, alarms
and timers almost always reach the right tool. It's tiny (13.7 MB, about 1.4 s per note) and
fully local.

Its weaknesses are consistent rather than random, which is why rules can cover them:

1. **It can't decline.** Every utterance becomes *something*: "Thank you" became a shopping
   item. Piatra keeps such voice notes as notes (`agent-rules.md`, section 3).
2. **It misreads some phrasings:** "remember to" as a note, an unknown "packing list" as
   shopping, reminders without a time as shopping items.
3. **It's unreliable on details:** times ("two twenty seven" → 2:00, "in one minute" → 1:00,
   a made-up "in 1pm"), numbers written as digits, and reworded text.

Retraining the model isn't practical here, so the approach is **rules and corrections around
the model, each one backed by a test case**. A shortcut phrase decides without the model; a
correction fixes a known mistake after it; the tools prefer your own words over the model's
wherever the model tends to garble them.

**Other languages:** the multilingual speech model transcribes them, and the model often
picks the right tool, but the time parser and the rules only understand English, so times
and list cues in other languages are missed.
