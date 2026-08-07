# Play Store listing — en-US

The text of the Google Play listing, kept here so it can be reviewed and diffed like the rest of
the app's words. The Play Console is where it takes effect; this file is where it is written.
What it replaced is in `git log`, so no copy of the old wording is kept below.

Write it from `docs/glossary/en.md` — the listing is user-facing text and takes the app's own
vocabulary. Two consequences that catch every rewrite: what the reader gets is a **trip**, while a
*track* is only the recorded path of one, and the app **records**, it never *tracks*.

**But the reader here has not installed the app**, which is the one place the glossary stops short.
A term the interface teaches — *capture radius*, *positioning* — arrives on this page unexplained,
and a term from the implementation — *gazetteer*, *basemap*, *vector tiles* — was never the
reader's in the first place. Say the plain thing, even where a screen in the app says otherwise.

Describe the build that is **live on Play**, not `main`. A feature merged after the last uploaded
`v1.0-vc<N>` tag is not something a reader can install, and promising it here is the one kind of
inaccuracy the store punishes.

The screenshot captions are not repeated here — they live in `CAPTIONS` in
`tools/generate_store_assets.py`, beside the frame that renders them.

## What is in this folder

Everything the Play Console takes, so a release is one directory rather than a hunt: this text,
`feature-graphic.png`, `play-icon.png`, and `screenshots/`. **One set of screenshots fills the
phone, 7-inch and 10-inch tablet slots** — all three accept 9:16, and 1080×1920 clears every
minimum — so upload the same six to each. **All the art is generated** —
`tools/generate_store_assets.py` writes the graphic and the
screenshots, `tools/generate_icon.py` the icon — so edit the generator, never the PNG. The device
captures composited into the frames are `docs/screenshots/`, the same shots the README embeds:
one set, so a re-shoot cannot leave the store showing an older app than the README does.

## App name

Play's limit is 30 characters. **This is not the launcher label** — that is `app_name` in
`res/values/strings.xml`, which stays the bare "Breadcrumb" and is deliberately
`translatable="false"`. The two fields are independent, and syncing them would put a search phrase
under the icon on someone's home screen.

The descriptive half is *location history* while the short description below leads with *timeline*:
both are glossary terms for what the app holds, and Play indexes the two fields together, so
spending them on one word would buy nothing.

```
Breadcrumb: Location History
```

## Short description

Play's limit is 80 characters.

```
A timeline of everywhere you go, recorded by itself. All data stays on-device.
```

## Full description

Play's limit is 4000 characters.

```
Breadcrumb quietly records where you go, so you don't have to remember to press "start".

RECORDS BY ITSELF
Turn Auto recording on once and forget it. Breadcrumb detects when you start walking, running, cycling or driving, records until you stop, and labels the trip with how you moved. Motion it cannot name is recorded as Moving, and you can change how any trip was labelled afterwards — including to taxi, boat, public transit or flight. No buttons, no forgotten recordings.

YOUR DAYS AS A TIMELINE
The timeline reads like a diary: trips and the stays between them, day by day, under each day's totals. While you are away, times are read on the clock of the place they happened in, not your phone's.

THE PLACES YOU RETURN TO
Recurring stays cluster into places you name — home, work, the gym — and the timeline fills with familiar names instead of coordinates. Each place takes a category and an area you can size to fit, and the categories the app offers you are learned from the ones you have already tagged. The Places tab holds them all, on a map and as a sortable list, each with its own visit history.

INSIGHTS
Journeys: consecutive nights away from home become one journey, named after where the time actually went, with per-year totals of journeys, nights, cities and countries.
Statistics: one month's movement and places, read against the year behind it.

FILL IN WHAT WASN'T RECORDED
Enter a trip by hand from two pins and two times — a flight, or anything the recorder missed — straight from the gap that shows it, and edit it afterwards. Merge trips that a short stop split, or split one that should have been two.

EVERY TRIP ON A MAP
The route is drawn on a detailed map, dark or light to match your theme, coloured point by point by speed, elevation, accuracy, satellite count or signal strength — alongside the stops you made along the way and the named places at each end.

YOUR HISTORY STAYS YOURS
• No account. No cloud. No ads. No analytics.
• Everything is stored only on your device.
• Back up the whole history to a single file, and restore it.
• Import and export GPX — one trip, a whole day, or everything at once.
• A deleted trip waits in Recently deleted before it goes for good.
• Optional app lock, opened by your fingerprint or the phone's own PIN.
• The network carries map imagery, plus one thing you can switch off: an optional online place search, which sends the words you type. City and town names come from a list built into the app, so naming a journey needs no network at all.

BUILT FOR BATTERY
Recording starts only when you actually move, and GPS switches off the moment you stop — including when movement is detected but no position ever arrives.

GOOD TO KNOW
• Requires "Allow all the time" location permission — that is what background recording is, and Android requires a visible notification while it runs.
• Uses Google Play Services for movement detection.
• Reads in English, Portuguese and Russian.
• Open source: github.com/valeronm/breadcrumb
```

