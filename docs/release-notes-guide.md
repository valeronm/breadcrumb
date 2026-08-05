# Release notes guideline

How to write the Play Console "What's new" text for a Breadcrumb release.

## Audience

Release notes are for testers/users, not developers. Describe what people will
**see** or **feel** in the app — never internals (services, receivers,
broadcasts, wakelocks, refactorings, build/tooling changes).

- ✅ "Stops during a trip are now highlighted on its map"
- ✅ "Fixed cases where recording could fail to start or stop on its own"
- ❌ "Close goAsync lifecycle gaps in transition delivery"
- ❌ "Derive versionName from git"

## Writing the notes

1. Find the range: commits since the last build **uploaded to Play** — that's
   the latest `v1.0-vc<N>` tag (`git log v1.0-vc<N>..HEAD`). Only uploaded
   builds get tagged; version bumps alone don't count (some versionCodes never
   ship).
2. Bucket the commits: user-visible feature, user-felt fix, internal-only.
   Drop the internal-only bucket entirely; several related internal fixes may
   collapse into one user-felt bullet ("more reliable automatic recording").
3. Write short bullets, most interesting first: new features, then fixes.
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
   - ✅ "Detected stops are named after their city"
   - ❌ "Stops you never named show the city they are in"

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
4. Keep it under Play's **500 characters per language**. 2–4 bullets is the
   sweet spot; if there are more, the release is probably overdue anyway.

## Format

```
What's new:
• <most notable feature or improvement>
• <fix users would have noticed>
```

## Versioning reminder

- `versionName` is derived from git (`1.0+<sha>`, `-dirty` if uncommitted) —
  never upload a `-dirty` build; commit first, then build.
- `versionCode` is bumped manually in `app/build.gradle.kts` and must increase
  for every upload. Gaps are fine.
- Building the bundle: push a `v1.0-vc<N>` tag on the bump commit — the
  Release workflow builds the signed `.aab` and attaches it to a GitHub
  Release (it fails if N doesn't match the committed `versionCode`).
- **Never build the release locally.** The bundle that ships is the workflow's:
  it alone has the upload keystore and the Protomaps key from repo secrets, so
  a local `assembleRelease` is not the artifact under any circumstances. Running
  one before the bump is committed is worse than pointless — `git describe`
  stamps it `-dirty`, so it validates a build that could not have shipped
  anyway. The sequence is bump → commit → tag → push tag → wait for CI.
