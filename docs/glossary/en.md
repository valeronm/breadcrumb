# English

The base language, and the canonical file: what each concept *means* is written here once, and every
other language file answers the same concepts in the same order with its own words. The strings live
in `res/values/`. See the [README](README.md) for how the groups are chosen and what belongs where.

## Terms

### Core

| Concept | Term | What it means |
|---|---|---|
| timeline | timeline | The day-by-day account of where someone has been, and the app's reason to exist. Made of trips and stays, and nothing else. |
| trip | trip | One continuous stretch of movement, with a start and an end, each of them a time and a place. Recorded, imported, or entered by hand. Never a *route*, *path* or *trail*. |
| stay | stay | Time spent in one place, and what separates two trips on the timeline. The row's verb form is *stayed*. |
| place | place | The saved entity: a name, a pin, a capture radius. English has **no second noun** for a location — where a sentence must mean "not one of these" it takes an adverb instead ("somewhere you stopped", "nights spent elsewhere"), because such a sentence exists precisely to say no place is involved. |

### The timeline

| Concept | Term | What it means |
|---|---|---|
| journey | journey | A run of nights away from home (Insights). The Insights concept and nothing else: a ferry crossing or a bus ride is a **trip**, however long it takes. |
| night-away | night away | A night not spent at home — what a journey is counted in. Insights' totals say plain "nights", the card's subject being journeys already; the timeline band, which stands alone, says "nights away". |

### A trip

| Concept | Term | What it means |
|---|---|---|
| track | track | The recorded *path* of a trip — the points between its two ends. A detail a trip may not have: one entered by hand stores only its bounds, and a trip the recorder missed entirely is a gap row with no track at all. |
| point | point | A position belonging to a trip, stored as a row of it — whether positioning found it, a file supplied it, or **the user placed it on the map**, which is all a hand-entered trip's ends are. Rejected ones are *noisy*; one the phone guessed rather than measured from satellites is a *guessed position*. |
| activity | activity | The user's detected activity. Per-type words live in `strings_recorder.xml`, in standalone and inline forms. |

### A stay

| Concept | Term | What it means |
|---|---|---|
| stop | short stop (a timeline row) · stop (a halt while recording) | On the timeline, **a stay short enough that merging the trips either side absorbs it** — the row takes the name on exactly that test, `TrackMerge` would take it, so it names what can be undone about the row rather than how long it lasted, and it is always *short stop*. The auto-pause settings use the bare word for its plain sense, a halt while recording, which is what those pages are about. Bare "Stop" alone is the button that ends recording. |
| visit | visit | **A stay, seen from the place's side.** Not a second thing: the same afternoon is a *stay* on the timeline and a *visit* to the café, and the code counts visits by grouping stays. Use it only on Places surfaces — a timeline row is never called a visit, and a place's history is never called its stays. |

### A place

*pin* and *point* live under different core concepts, which is what makes them so easy to confuse:
they are drawn alike and dropped alike, and nothing but this table says they are different things.

| Concept | Term | What it means |
|---|---|---|
| pin | pin | **A place's** marked coordinate, and only a place's — the thing its capture radius is drawn around. A coordinate the user drops while entering a trip is one of that trip's *points*, however alike the two look on a map. |
| capture-radius | capture radius | The circle around a pin that claims trip ends. |
| place-category | category | What a place is tagged as. The labels and their groups live in `strings_places.xml` — display text, free to reword and translate, over stored codes that are permanent. A language may well reuse a word from elsewhere in this table for one of them; say so in its file rather than inventing a synonym to keep them apart. |

### Actions

| Concept | Term | What it means |
|---|---|---|
| merge | merge | Combining trips into one. A short stop a merge dissolves is *merged away*. |
| split | split | Cutting one trip into two. |
| delete | delete | Destroying data. |
| remove | remove | Detaching an entity (a place, a pin). Never for destroying: Recently deleted says trips are *deleted* forever. |
| clear | clear | Emptying a list (logs, Recently deleted). |
| undo | undo | Reverting the last action. |
| restore-trip | restore | Putting one trip back from Recently deleted — "Restore trip". |
| restore-backup | restore | Rebuilding everything from a backup file — "Restore from backup". The object carries the difference; English does not split the verb. |
| reset | reset | Returning settings to their defaults. |

### The app

| Concept | Term | What it means |
|---|---|---|
| recording | recording | The act and state of capturing trips — one of the ways a trip arrives, beside importing and typing one in. Never *tracking*. |
| positioning | positioning | Working out where the phone is — the process, from switching the receiver on to accepting a point. Never *fix*, and never *signal*. |
| backup | backup | The full-app export: one file with everything. |
| search | search | The user typing a query (places, cities). The only search there is — what the receiver does is *positioning*. |
| lock | lock / unlock | The app lock, **as Settings names it**, where someone is hunting for the feature and needs the word every other app uses. The barrier itself never says it — see the decision below. |
| logs | logs | Diagnostic entries the app writes about itself. The entries are never translated; only the screen's chrome is. |

## Conventions

- Sentence case throughout — titles, buttons, chips ("Recently deleted", not "Recently Deleted").
- American English ("Gas station", "Kilometers").

## Decisions

Why a rival was refused, where the Terms table can only say that it was. Each holds for sentences
nobody has written yet, which is what earns it a place here rather than a comment beside one string.

- **What a reader loses is a journey, not a file.** Detection stalling costs someone the trip; a
  track is only what we would have had of it. Any sentence about consequence to the reader takes
  *trip*, whichever surface it is on — the deafness notification included, where nothing is
  hand-entered.
- **The radio is not the subject.** *Fix* is GPS's term of art and *signal* describes the receiver's
  reception; neither is what a status line reports. The reader wants to know whether the app knows
  where they are, which is **positioning** (the process) and **point** (what it found). *Signal*
  belongs to the carrier-to-noise metric alone, where it means signal **strength**.
- **A state's live and settled phrasings describe one state.** Where the same recorder state has a
  form with figures and a form without, the two must agree on what is happening: one cannot report
  progress while the other says something is missing. A guard that has switched GPS off is not
  waiting for GPS — it is waiting for movement, and says so.
- **The act of capturing is *recording*, never *tracking*.** The second word names a thing the app
  does not have a concept for, and drags the artifact sense of *track* in with it.
- **A file format is not vocabulary.** GPX is named only where a reader must recognise a file by its
  extension; everywhere else the thing is a *track* and the format stays underneath.
- **A stay and a visit are one event under two names**, chosen by the surface rather than the data.
  Refuse mechanical definitions of *visit* ("a trip starting or ending inside a place") — they
  describe detection rather than the thing, and read literally they count an arrival and a departure
  separately.
- **A word earned on one screen does not travel.** *Stop* is the mergeable timeline row and the
  recorder's halt, and neither a place nor a dwell inside a trip: `DwellDetector`'s output is a
  *stay* (its own KDoc calls them embedded stays), and a place seldom visited is described off
  `visit`.
- **Drawn alike is not the same as being alike.** A pin and a trip's end coordinate are dropped the
  same way and rendered the same way, and are different things: only one persists as a place with a
  capture radius, and the other is that trip's *point*. (*PIN* in the app-lock text is the platform's
  screen-lock code, unrelated to both, and stays because Android's own settings call it that.)
- **A page's title and its subtitle name one concept, not two.** If the subtitle has to reach for a
  second word to explain the title, the title is wrong.
- **A state the reader is already living in is not news, and stating it sounds like a refusal.** A
  barrier says what it is *for*, and the button carries the action. Elsewhere *locked* stays exactly
  because it reassures — "Recording is never locked" is the sentence that needs the word.
- **An acronym already containing the word does not take it again** — *Positioning*, never "GPS
  positioning".
