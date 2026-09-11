# Play Store listing — en-US

The text of the Google Play listing, kept here so it can be reviewed and diffed like the rest of
the app's words. The Play Console is where it takes effect; this file is where it is written.
What it replaced is in `git log`, so no copy of the old wording is kept below.

Write it from `docs/glossary/en.md` — the listing is user-facing text and takes the app's own
vocabulary. Two consequences that catch every rewrite: what the reader gets is a **trip**, while a
*track* is only the recorded path of one, and the app **records**, it never *tracks*.

**But the reader here has not installed the app**, which is the one place the glossary stops short.
A term the interface teaches — *capture radius*, *positioning* — arrives on this page unexplained,
and a term from the implementation — *city atlas*, *basemap*, *vector tiles* — was never the
reader's in the first place. Say the plain thing, even where a screen in the app says otherwise.

Describe the build that is **live on Play**, not `main`. A feature merged after the last uploaded
`v<version>` tag is not something a reader can install, and promising it here is the one kind of
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
A timeline of everywhere you go, recorded by itself. Data stays on your phone.
```

## Full description

Play's limit is 4000 characters.

```
Breadcrumb quietly records where you go. You never have to remember to press "start".

RECORDS BY ITSELF
Turn on Auto recording once and forget it. Breadcrumb notices when you start walking, running, cycling or driving. It records until you stop and labels the trip with how you moved. You can change the label later, even to taxi, boat, public transit or flight. No buttons, no forgotten recordings.

YOUR DAYS AS A TIMELINE
The timeline reads like a diary. It shows your trips and the stays between them, day by day, with totals for each day. Times abroad show in local time.

THE PLACES YOU RETURN TO
Name the spots you keep returning to, like home, work or the gym. The timeline then shows those names. The Places tab shows all your places on a map and in a list. Each place has its own visit history.

INSIGHTS
Journeys: nights away from home in a row become one journey. It is named after the places where you spent most of it. Each year adds up your journeys, nights, cities and countries.
Statistics: one month of trips and places, compared with the year behind it.

FILL IN WHAT WASN'T RECORDED
Add a trip by hand, like a flight or anything the app missed. You can edit it later. Merge two trips that a short stop split, or split one trip into two.

EVERY TRIP ON A MAP
Each trip is drawn on a detailed map. The line is coloured by speed or elevation. The map also shows the named places at each end.

YOUR HISTORY STAYS YOURS
• No account. No cloud. No ads. No analytics.
• Everything stays on your phone.
• Back up your whole history to one file and restore it.
• Import and export GPX files: one trip, a whole day, or everything.
• A deleted trip waits in Recently deleted before it goes for good.
• Lock the app with your fingerprint or PIN, if you want.
• The app goes online only for maps and for a place search you can turn off.

BUILT FOR BATTERY
Recording starts only when you move. GPS turns off the moment you stop.

GOOD TO KNOW
• Needs location access set to "Allow all the time" to record in the background. Android shows a notification while it records.
• Uses Google Play Services to detect movement.
• Available in English, Portuguese and Russian.
• Open source: github.com/valeronm/breadcrumb
```

