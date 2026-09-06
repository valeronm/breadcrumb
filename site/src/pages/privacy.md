---
layout: ../layouts/Page.astro
title: Privacy
description: What Breadcrumb records, where it is kept, and the few things that leave the phone.
---

# Privacy

Breadcrumb keeps a history of where you have been. The app keeps it on your phone and sends it
nowhere on its own. There is no account, no server, no analytics and no advertising. This page says what the
app records, where it keeps it, and the few things that leave the phone.

## What the app records

- **Your position**, while a trip is being recorded: latitude, longitude, altitude, speed,
  heading, the time, how accurate each position was, and how many satellites the phone could see.
- **Your detected activity**, such as walking, cycling or being in a vehicle. It comes from
  Google Play Services' activity recognition on your phone, and is the main thing that starts and
  stops recording.
- **What you enter yourself**: the places you name, their categories, the trips you add or edit
  by hand, and any GPX files you open in the app.

Everything else the app shows is worked out on the phone from those records: the stays between
trips, the places you return to, and journeys away from home.

## Where it is kept

The app keeps the history in its private storage on your phone. Uninstalling the app deletes it,
unless your Android backup holds a copy. The history is kept until you delete it. A trip you delete, or one the app judged too
short to keep, goes to Recently deleted. You can restore it there or clear the list. After two
weeks the app deletes it for good the next time it starts. The app has no server. It sends your history
nowhere on its own.

If Android's own backup is turned on for your phone, the app's database is included in it. That
backup goes to your Google account under Google's terms. Setting up a new phone from this one with
Android's transfer copies the database too. You turn both off in your phone's settings, not in the
app.

You can lock the app behind your phone's unlock and hide it from screenshots and the recent-apps
screen, under Settings → App lock. Recording carries on whether the app is locked or not.

## What leaves the phone

- **Map tiles.** The maps are drawn from tiles served by [Protomaps](https://protomaps.com/).
  Loading a map sends Protomaps which tiles it needs and your network address. Which tiles it needs
  tells them what area of the map you are looking at. Nothing from your history goes with it. The
  map's fonts and icons are Protomaps' files served from GitHub, which sees your network address
  when they load.
- **Online place search**, if you use it. When you add a trip by hand, you can search for a place
  by name. That search sends the words you typed to [Photon](https://photon.komoot.io/). Once
  the trip's starting point is on the map, the search also sends that point, so the results are
  the ones most relevant to your trip. Until then it sends the destination, if that one is placed.
  You can turn the search off under Settings → Online services.
  The app then searches only the list of cities built into it.
- **What you export.** A trip shared as a GPX file, or a backup of your whole history, goes
  wherever you send it. The app never sends it anywhere on its own.
- **The log**, if you share it. The app keeps a log on the phone for troubleshooting. It reaches
  back several weeks, and you can clear it or share it under Settings → Logs. It holds no
  positions. It does hold when each trip started and ended, how long and how far it was, how you
  moved, how far the phone moved from where it last stopped, the name of any file you opened in
  the app, and the app's version.
- **Rough positions between trips.** Activity recognition runs through Google Play Services on
  your phone. So does the watch for you leaving where you stopped, which is how the app notices a
  trip that activity recognition missed, such as a train. Play Services is given the spot to watch,
  where you last stopped, or wherever the phone last was when you turned recording on or restarted
  the phone, and supplies rough positions while you are there. The app does not keep those
  positions. You can turn the watch off under Settings → Starting a trip. How Play Services handles
  all of this is covered by Google's privacy policy, not this one.

## Permissions

- **Location, including in the background.** The app records location while it is not on screen,
  and while it is closed. Turning Auto recording off stops that.
- **Physical activity.** To know when a trip starts and ends.
- **Notifications.** Android requires a visible notification while location is recorded in the
  background. The app also uses one to tell you when activity detection has stopped responding.
- **Run at startup, and ignore battery optimisations.** So recording resumes after a reboot and
  the phone does not stop it to save power.
- **Network.** For the map tiles and the optional place search above.
- **Fingerprint.** For the app lock, if you turn it on.

## The viewer

The [viewer](/viewer/) on this site opens a backup file in your browser. The viewer reads the file
and keeps a copy in your browser's own storage. It stays there until you clear the site's data in
your browser. It is not uploaded. The only network use is the map, from Protomaps,
as in the app.

## This site

This site is hosted on GitHub Pages. GitHub's servers keep access logs. The site itself sets no
cookies and runs no analytics.

## Changes

This page is the policy. Its history is in the
[source repository](https://github.com/valeronm/breadcrumb). If you have a question about it, open
an issue there.
