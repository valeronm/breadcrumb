# Vocabulary glossary

One term per concept, per language. This is the record of what the app's words *mean* and which
word each language uses for them — the decisions a string file can apply but cannot explain. Use it
two ways:

- **Writing a string**: take the term from your language's file. If the concept isn't in the spine
  below, it is either not a recurring concept (fine — write the sentence) or a new one (add it to
  the spine and answer it in every language file).
- **Reviewing**: read each `res/values*/strings_*.xml` against its language's file. A string using
  a rival term for a listed concept is a finding; either the string moves to the glossary's word or
  the language file records why this spot is an exception.

The structure mirrors the resources: the **concept spine** below is the base (its terms are the
English `values/` vocabulary), and each language answers it in its own file, the way `values-pt`
answers `values/` — one file per language, named by the locale qualifier:

- [`en.md`](en.md) — English (the base language)
- [`pt.md`](pt.md) — Portuguese (European)

**Adding a language = adding a file**: copy `pt.md`'s shape, answer every concept in the spine, and
write the language's conventions and decisions. Nothing in the spine or the other files should need
to change. A language file with fewer rows than the spine has unanswered concepts — that is what a
review compares first.

The *mechanics* of resource writing — whole sentences, no fragments, placeholders, plurals, inline
vs standalone word forms — are documented in `CLAUDE.md` and in the string files' own comments, and
are partly machine-checked by `ResourceHygieneTest`. The glossary is only about which words the
sentences use.

## Concept spine

| Concept | English (base) | What it means |
|---|---|---|
| track | track | The recorded artifact: one continuous stretch of movement. Not the human event. |
| trip | trip | A journey the user enters by hand: two pins, two times. |
| journey | journey | A run of nights away from home (Insights). |
| travel-category | Travel | The place category. |
| place | place | The saved entity: a name, a pin, a capture radius. |
| spot | somewhere / a spot | A generic point on the ground — deliberately *not* the entity above. |
| stay | stay | Time spent at one place, as a timeline row (noun). The row's verb form is *stayed*. |
| stop | stop | A halt: short stop, detected stop, rare stops. |
| timeline | timeline | The derived day-by-day view, and its tab. |
| recording | recording | The act and state of capturing tracks. |
| point | point | A recorded fix as a row of the track; rejected ones are *noisy*. |
| fix | fix | A GPS position ("GPS fix", "satellite fix"). |
| pin | pin | A place's marked coordinate. |
| capture-radius | capture radius | The circle around a pin that claims track ends. |
| backup | backup | The full-app export: one file with everything. |
| merge | merge | Combining tracks into one. A short stop a merge dissolves is *merged away*. |
| split | split | Cutting one track into two. |
| delete | delete | Destroying data. |
| remove | remove | Detaching an entity (a place, a pin). |
| clear | clear | Emptying a list (logs, Recently deleted). |
| undo | undo | Reverting the last action. |
| restore-backup | restore | Rebuilding everything from a backup file. |
| restore-track | restore | Putting one track back from Recently deleted. |
| reset | reset | Returning settings to their defaults. |
| search-text | search | The user typing a query (places, cities). |
| search-gps | search | The receiver looking for a position. |
| lock | lock / unlock | The app lock. |
| visit | visit | One track starting or ending inside a place. |
| night-away | night away | A night not spent at home. |
| activity | activity | The user's detected activity. Per-type words live in `strings_recorder.xml`, in standalone and inline forms. |
| logs | logs | Diagnostic entries. The entries themselves are never translated; only the screen's chrome is. |
