# Release notes guideline

How to write the Play Console "What's new" text for a Breadcrumb release.

## Audience

Release notes are for testers/users, not developers. Describe what people will
**see** or **feel** in the app — never internals (services, receivers,
broadcasts, wakelocks, refactorings, build/tooling changes).

- ✅ "Stays during a trip are now highlighted on its map"
- ✅ "Fixed cases where recording could fail to start or stop on its own"
- ❌ "Close goAsync lifecycle gaps in transition delivery"
- ❌ "Derive versionName from git"

## Writing the notes

1. Find the range: commits since the last build **uploaded to Play** — that's
   the latest `v<version>` tag (`git log <tag>..HEAD`). Only uploaded
   builds get tagged; version bumps alone don't count (some versionCodes never
   ship).
2. Bucket the commits: user-visible feature, user-felt fix, internal-only.
   Drop the internal-only bucket entirely; several related internal fixes may
   collapse into one user-felt bullet ("more reliable automatic recording").
   These buckets decide the version bump as well as the text — see
   [Which part to bump](#which-part-to-bump), and do both from one reading of
   the range rather than classifying it twice.
3. **Check each surviving claim against the last release, not against the
   commit that prompted it.** A subject says what its author changed, which is
   rarely what a reader gains — "switch the Places map and list with a
   segmented control" changed a *switcher*, and reads as though the map were
   new. Name the thing the bullet promises and look for it at the tag:

   ```bash
   git show <last-tag>:<file> | grep -c '<symbol>'
   ```

   Present in both, and the bullet either goes or is rewritten down to the part
   that changed. A zero is the start of the answer rather than the end of it —
   a symbol that was merely *renamed* since the tag counts zero too, so check
   what the old name was before believing it. Do this before
   wording anything: every bullet this has caught was already well-written, and
   the rules below cannot see a claim that is merely untrue.
4. **Then ask whether a reader would feel it.** Step 3 answers whether a change
   is new, and a change can be new, correctly described, and still invisible on
   screen — `MaterialTheme` → `MaterialExpressiveTheme` reads as a restyle in
   the diff, yet with the app's own colours and type passed in it leaves almost
   nothing to point at. Confirm such a bullet against the running app rather
   than the diff. Only whoever has the build in their hand can answer, so draft
   the bullets while there is someone to ask, and ask before the bump is
   committed: withdrawing one changes the version, since a range whose only
   feature-bucket item was that bullet is a patch rather than a minor.
5. Write short bullets, most interesting first: new features, then fixes.
   Plain language, no commit references, no jargon. One line per bullet —
   state the change and stop; cut qualifiers, parentheticals, and trailing
   explanations ("— existing ones are cleaned up on first launch").
   - ✅ "Empty trips are deleted right away, not left in Recently deleted"
   - ❌ "Truly empty trips are now deleted immediately instead of piling up
     in Recently deleted — existing ones are cleaned up on first launch"

   Three faults keep recurring, all of them caught in review rather than in
   writing — so check for them deliberately:

   **Call the thing what the app calls it.** A bullet that *describes* a screen
   element instead of naming it was written from the code; the reader can only
   match the note to what they see if both use the same word. Which word that is
   comes from `docs/glossary/` — a note saying *track* where the screens say
   *trip* is the same fault wearing a different hat.
   - ✅ "Detected stays are named after their city"
   - ❌ "Stays you never named show the city they are in"

   **Don't let a bullet trail off on a preposition.** Reading each one aloud
   catches this in a second; re-reading them does not.
   - ✅ "Times abroad show in local time"
   - ❌ "Times show on the clock of the place they happened in"

   **One change per bullet.** An "and" joining two unrelated changes reads as
   neither. Usually the answer is not to split it but to drop the lesser half —
   if it didn't earn its own bullet, it doesn't earn half of one.
   - ✅ "A trip's map breaks the line where recording paused"
   - ❌ "A trip's screen says where its fixes came from and where recording
     stopped watching"
6. Keep it under Play's **500 characters per language**. 2–4 bullets is the
   sweet spot; if there are more, the release is probably overdue anyway.

## Format

```
What's new:
• <most notable feature or improvement>
• <fix users would have noticed>
```

## Versioning reminder

- The marketing version is `major.minor.patch` (the `versionName` in
  `app/build.gradle.kts`), bumped by hand when preparing an upload — which
  part is [below](#which-part-to-bump). Every upload bumps it, patch by
  default, and the tag being the version is what enforces that.
- `versionName` is the marketing version and nothing else — no sha, no
  variant, no versionCode. What a build *is* is split three ways in
  `util/BuildIdentity`, combined only for display: the version, the variant
  (`debug`/`perf`/`demo`, absent on release) and the commit
  (`BuildConfig.GIT_SHA`, `-dirty` when built over uncommitted changes, so
  not a build that may be uploaded). Settings shows version and variant; the
  recorder's arming line logs all three.
- `versionCode` is bumped manually in `app/build.gradle.kts` alongside the
  version and must increase for every upload. Gaps are fine. It appears in
  **neither the UI nor the logs**, and no longer on the tag either — it is
  Play's vocabulary and nothing in the app reads it. Nothing else would catch a
  bump forgotten beside the version, so the release workflow reads the build
  file at every previous tag and refuses one that does not exceed them all —
  and refuses just as loudly if it parses no versionCode at any of them, a
  guard over history being one that can only go blind, never stale.
- Building the bundle: push the bump commit to `main`, then push a `v<version>`
  tag on it (e.g. `v1.0.1`) — the workflow fires on the tag, but a commit no
  branch contains is not a release. When later commits must stay local,
  `git push origin <bump-sha>:main` pushes the bump on its own. The Release
  workflow builds the signed `.aab` and attaches it to a GitHub Release (it
  fails unless the tag is exactly `v` plus the committed `versionName`, and
  unless the `versionCode` exceeds every previously tagged one).
- **Never build the release locally.** The bundle that ships is the workflow's:
  it alone has the upload keystore and the Protomaps key from repo secrets, so
  a local `assembleRelease` is not the artifact under any circumstances. Running
  one before the bump is committed is worse than pointless — it is stamped
  with a `-dirty` sha, so it validates a build that could not have shipped
  anyway. The sequence is bump → commit → push → tag → push tag → wait for CI.

## Which part to bump

SemVer's own rules are a contract with *callers* — major means their code
breaks. An app has no callers, so there is nothing to derive a bump from
mechanically and any rule here is a convention. This one keys off the buckets
step 2 already sorts the range into, so the notes and the number are one
judgement rather than two that can disagree.

**Every upload bumps the version** — patch is the default, and something larger
only displaces it. Nothing may ship twice under one version: the tag *is* the
version, so a repeat is a tag git refuses to create rather than a mistake
discovered later.

Decided **once per upload**, over the whole `git log <tag>..` range — not per
commit, so a range holding both a feature and a fix is a single minor bump:

- Anything in the **user-visible feature** bucket → **minor**, patch back to 0.
- Only **user-felt fix** bullets → **patch**.
- Everything fell in **internal-only**, so the notes are empty → **patch**.

**Major is declared, never derived.** It is not reached by accumulating minors;
ten of them still leave the app what it was. Bump it when the reader has to
relearn the app (navigation or the core model reshaped), when the update
strands or demands action on their existing data (the schema floor raised past
the installed base, a backup format that older builds can't read), or when what
the app is for changes — server sync would be that.

**Watch for patch withering.** Nearly every release carries some visible
improvement, so nearly every release is a minor and patch may go unused. If
several releases pass without one, the third component is decoration and the
honest scheme is a two-part `major.minor` — that is a decision to take
deliberately, not a drift to allow.
