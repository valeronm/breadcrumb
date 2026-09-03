# Vocabulary glossary

One term per concept, per language. This is the record of what the app's words *mean* and which
word each language uses for them — the decisions a string file can apply but cannot explain. Use it
two ways:

- **Writing a string**: take the term from your language's file. If the concept isn't listed, it is
  either not a recurring concept (fine — write the sentence) or a new one, in which case add it to
  [`en.md`](en.md) and answer it in every other language file.
- **Reviewing**: read each `res/values*/strings_*.xml` against its language's file. A string using
  a rival term for a listed concept is a finding; either the string moves to the glossary's word or
  the language file records why that string is an exception.

**Scope is the app's own screens**: `res/values*/`, plus the `README.md` and `docs/` that describe
them to a reader. The `site/public/viewer/` companion viewer is **out** — it ships no translations and is read by
whoever runs it, so holding its handful of English strings to this costs more than it buys. Code is
out too, identifiers and prose alike: a comment saying "detected stop" beside a row now labelled
"Detected stay" is describing the mechanism, not addressing anyone.

## The files

One per language, named by the locale qualifier:

- [`en.md`](en.md) — English. **The canonical file**: it carries the concepts, what each one means,
  and English's word for it. Every other file answers the same concepts in the same order, because
  `values/` is what the translations are translations of.
- [`pt.md`](pt.md) — Portuguese (European)
- [`ru.md`](ru.md) — Russian

**Adding a language = adding a file.** Copy `en.md`, replace each term with your language's, and
rewrite the conventions and decisions for the language you are writing. Keep the sections and their
order — that is what lets two files be read side by side, and it is the first thing a review
compares. A translation file records only what is *its own*: the word it chose, and the notes and
decisions behind it. What a concept means is not restated per language; it is in `en.md` once.

The *mechanics* of resource writing — whole sentences, no fragments, placeholders, plurals, inline
vs standalone word forms — are documented in `CLAUDE.md` and in the string files' own comments, and
are partly machine-checked by `ResourceHygieneTest`. The glossary is only about which words the
sentences use — plus the writing conventions below, which every language shares.

## Conventions shared by every language

- **A toggle's label names what *on* does, stated positively.** A negated label ("Don't dim the
  screen") turns the off state into a double negative, and the reader has to solve it to know what
  the switch is set to. Subtitles and descriptions are free to explain in whatever polarity reads
  best; the label beside the switch is not.

- **A single sentence or fragment takes no final period; a text of several sentences keeps every
  period, the final one included.** This is the consensus of the platform style guides — [Material 3
  grammar and punctuation](https://m3.material.io/foundations/content-design/style-guide/grammar-and-punctuation)
  ("don't place periods after body text if there is only a single sentence"), with the
  [Microsoft Style Guide](https://learn.microsoft.com/en-us/style-guide/punctuation/) drawing the
  same line for labels, headings and buttons — and it keeps UI text scannable. An abbreviation's
  dot (Russian «сп.», «дн.») is spelling, not punctuation, and stays. A language file does not
  restate this; it records only what is its own.

## Why these are the core concepts

The concepts and their meanings are in [`en.md`](en.md); what this section argues is which of them
are grouped as **Core** and why the loud ones around them are not.

The app is one thing: a **timeline** of where someone has been, made of **trips** and the **stays**
that separate them, and nothing else. The **history** is core alongside it, being what that
timeline is an account *of* — a distinction the app leans on wherever a sentence is about the data
rather than the screen showing it. **Places** are core too, and are what make the rest legible — a
stay's row is labelled by the place holding it, and a journey is defined as a run of nights away
from a place tagged Home.

**Recording is not core**, though it is the loudest thing the app does. A trip does not need it to
exist: an imported trip and a hand-entered one never went near the recorder. So recording is one of
the ways a trip arrives, which makes it machinery.

**A track is not a kind of row** either. Whether a trip carries one — and whether the recorder, a
GPX file or the user's own typing produced it — is a fact *about* that trip rather than what it is.
The gap row makes this concrete: a trip we know happened, because the positions either side of it
disagree, carrying no track whatsoever. That is why the row offers to fill it in, and why the
form's state is a `TripDraft`.

Which concept a string is reaching for follows from **what its sentence is about**:

- where and when someone went → **trip**. Merging, splitting, deleting and restoring; the detail
  screen and its type; the keep thresholds; the empty states; a trip in progress.
- time spent somewhere, as its own row → **stay**, never *stop* or *visit*.
- the recorded path, its points, or the file holding them → **track**.

Resource *names* are not held to this, and neither is the code. `track_split_confirm` keeps its name
while its text says *trip*, exactly as `activity_ferry` reads "Boat" — a resource name is an
identifier, and renaming one costs every call site and buys a reader nothing. The `tracks` table and
the `Track*` domain types stay too: the storage really is a track, and the app already separates
permanent codes from display text everywhere else.

## How the concepts are grouped

Every language file carries these sections, in this order, and each concept sits under the thing it
is *about*:

- **Core** — the timeline, the history it accounts for, and what they are made of.
- **The timeline**, **A trip**, **A stay**, **A place** — what describes each core concept. A word
  belongs to whichever one it qualifies, which is how *stop* and *visit* come to sit under `stay`
  rather than beside it: both turn out to be a stay seen from somewhere.
- **Actions** — every verb the app offers, together. A button label is written by looking for the
  verb, not by working out which concept it acts on, and keeping them in one place puts the ones
  sharing a word next to each other where a reader can check them.
- **The app** — machinery: recording, positioning, backup, search, the app lock, the logs. None of
  it is a thing on the timeline.
