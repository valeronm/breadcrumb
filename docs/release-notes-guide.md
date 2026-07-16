# Release notes guideline

How to write the Play Console "What's new" text for a Breadcrumb release.

## Audience

Release notes are for testers/users, not developers. Describe what people will
**see** or **feel** in the app — never internals (services, receivers,
broadcasts, wakelocks, refactorings, build/tooling changes).

- ✅ "Stops during a track are now highlighted on the track map"
- ✅ "Fixed cases where tracking could fail to start or stop on its own"
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
   - ✅ "Empty tracks are deleted right away, not left in Recently deleted"
   - ❌ "Truly empty tracks are now deleted immediately instead of piling up
     in Recently deleted — existing ones are cleaned up on first launch"
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
