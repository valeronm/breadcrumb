# Russian

Answers the concepts in [`en.md`](en.md) with Russian words; the strings live in `res/values-ru/`.
What each concept *means* is written there once and not repeated here — this file holds only what is
Russian's own: the word, and the reasoning behind it.

## Terms

Sections and order follow `en.md`, so the two files read side by side.

### Core

| Concept | Term | Notes |
|---|---|---|
| timeline | история | The plain word for one's own record of the past — «ваша история», what someone would call this unprompted. *Хронология* was considered and passed over as clinical. |
| history | история | The same word as *timeline* — a deliberate merge, see decisions below. |
| trip | поездка | One word for every movement record, a walk included — see decisions below. Never *маршрут*, *путь*, *трасса*. |
| stay | посещение | The same word as *visit* — a deliberate merge, see decisions below. No verb form: the row leads with the noun; where a sentence needs a verb, «вы были» agrees with *вы* and so stays genderless. |
| place | место | Also Russian's only everyday noun for a location, so the empty states that mean "no saved place is involved" cannot switch nouns the way Portuguese does — they phrase around it instead: «там, где вы останавливались», «не дома», never a sentence whose subject is a *место* the same sentence denies exists. |

### The timeline

| Concept | Term | Notes |
|---|---|---|
| insights | Статистика | Not a calque: Russian has no everyday noun for *insights*, so the tab is named for what is read there. |
| journey | путешествие | Deliberately not a second *поездка* — see decisions below. |
| statistics | По месяцам | **Not the literal *Статистика***, which this language already spends on the tab above — see decisions below. |
| night-away | ночь вне дома | Insights' totals say plain «ночей»/«ночи», the card's subject being journeys already. |

### A trip

| Concept | Term | Notes |
|---|---|---|
| track | маршрут | Only the recorded path — everywhere the sentence is about the journey itself, the word is *поездка*. Never *трек* (jargon a GPX-minded reader knows but a plain one doesn't), *путь*, *след*. |
| point | точка | Rejected ones are *отклонённые* — never the calque *шумные* (which reads as loud, not as measurement noise) nor *выбросы* (which reads as emissions); a guessed one is *приблизительное местоположение*. |
| activity | активность | Where a settings sentence explains what is detected, «способ передвижения» may carry it — that names the *type*, not the concept. The **inline forms** of the carrier-borne types are headed by the trip's own noun — «поездка на велосипеде», «поездка на такси» — while the rest are bare («ходьба», «перелёт»): the sentences those forms drop into are about the trip, and a bare «велосипед» cannot be their subject. |
| movement | движение | The state, and the *Движение* activity label. A **counted stretch** is *перемещение* («перемещения с перерывом короче…») — the word names a piece of movement here, not a trip, which is why its rejection as trip's name doesn't ban it. |

### A stay

| Concept | Term | Notes |
|---|---|---|
| stop | короткая остановка (a timeline row) · остановка (a halt while recording) | |
| visit | посещение | The same word stay uses — one word on both surfaces, see decisions below. **A compact count in a row's subtitle is the exception**: it says «%d раз», counting occasions rather than naming the event, which is what a figure beside a duration is doing — the Places row's count and Statistics' per-category one both take that form, while anything naming the event keeps *посещение*. |

### A place

| Concept | Term | Notes |
|---|---|---|
| pin | метка | The word Russian map UIs use for a dropped marker, and safely apart from «PIN-код» in the app-lock strings. The add-trip form's coordinates are *точки*. |
| capture-radius | радиус охвата | |
| place-category | категория | The *Путешествия* category shares its word with journey, in the plural; the surfaces keep them apart, a chip in a picker against a band in Insights. |

### Actions

| Concept | Term | Notes |
|---|---|---|
| merge | объединить | *Merged away* is *поглощена* — the stay is absorbed, not joined. |
| split | разделить | |
| delete | удалить | Never *убрать*, which reads as tidying up. |
| clear | очистить | |
| undo | отменить | The verb; *cancel* is the noun «Отмена» — see decisions below. |
| restore-trip | восстановить | |
| restore-backup | восстановить | Like English, the object carries the difference; Russian does not split the verb. |
| reset | сбросить | |
| save | сохранить | Not «готово», which answers a different question — whether the reader is finished, not whether anything was kept. |

### The app

| Concept | Term | Notes |
|---|---|---|
| recording | запись | Progressive titles use «Идёт запись». Never *отслеживание* — that is *tracking*, the concept the app refuses. |
| setting-off | отправиться в путь | The page is «Начало поездки», the subtitle «что вы отправились в путь». The full phrase, not bare *выехать*, which commits to a vehicle — the same page has to cover a train and a taxi, and one of these triggers exists precisely because the app cannot tell what is carrying you. The fence row takes *уход* for leaving the spot, keeping the two motions distinct on one screen. |
| positioning | определение местоположения | The status line says «Определение местоположения…», never «Поиск GPS» or «Поиск сигнала» — *поиск* is the user's, and the radio is not the subject. |
| backup | резервная копия | Short form *копия*; the verb is «создать резервную копию». |
| search | поиск (noun), найти (verb) | Only the user's. What the receiver does is *определение местоположения*. |
| lock | блокировка; заблокировать / разблокировать | |
| logs | журнал | The entries are never translated; only the screen's chrome is. Kept apart from *запись*, the recorder — the one collision in this group to watch. |
| setup | настройка (noun), настроить (verb) | Not *настройки* (plural), which is the Settings hub: this is done once and then gone, where those are returned to. The singular is what keeps the two apart, so it must not drift to the plural. |

## Conventions

- The user is addressed as *вы*, lowercase — the capitalized «Вы» belongs to personal
  correspondence, not interface text. Requests take the plural imperative: «Разрешите»,
  «Включите», «Настройте».
- **Past tense about the user must agree with *вы***: «вы были», «вы останавливались» — plural, so
  genderless. Never a third-person-singular past («побывал», «остановилась») with the user as its
  subject: Russian genders it, and the app cannot know.
- Buttons take the infinitive: «Добавить», «Удалить», «Восстановить». The one nominal button is
  «Отмена» (cancel).
- Always «ё», never a bare «е» standing in for it: «ещё», «идёт», «объём».
- Every `<plurals>` provides *one*, *few* and *many*: «21 поездка», «2 поездки», «5 поездок» — and
  *one* covers 21, 101, …, so its string carries the number.
- A unit symbol cannot agree with its number, so the ladder's letters are invariable abbreviations
  and miles are the bare *миль* whatever the count.
- A placeholder cannot be declined, so a sentence is built to leave its placeholder in the
  nominative — «Место: %s», not a frame that needs «%s» in the genitive.
- Sentence case throughout, which is Russian's own norm — no title case exists to resist.
- Quotation marks are «ёлочки».

## Decisions

- **Stay and visit are both *посещение*.** One word for time spent at a place, on every surface —
  the candidates for a split (*пребывание*, *стоянка*) read as officialese or as parking. The
  merge is the same move Portuguese makes with *viagem* for trip and journey: the language keeps
  them apart by surface, a timeline row and a place's history being unmistakable contexts.
- **The timeline and the history are both *история*.** Russian's everyday word for one's own past
  covers the account and what it accounts for at once, and the rivals name a store rather than a
  life: *архив* and *данные* are what a service holds about someone, which is the opposite of what
  «ваша история не покидает устройство» promises. Surface keeps them apart — a tab, against a
  sentence about privacy or a backup file.
- **A trip is a *поездка* even on foot.** The only candidate that reads naturally in chrome
  («Добавить поездку», «Удалить поездку»). *Перемещение* is mode-honest but bureaucratic; *путь*
  names the path, which is the track's job. Colloquial Russian stretches *поездка* over a walk
  less happily than English stretches *trip* — the stretch is accepted, not unnoticed.
- **A journey is a *путешествие*, not a second *поездка*.** Russian genuinely has a word meaning
  exactly a run of nights away, so trip and journey never share one. Where Portuguese merged for
  lack of a natural rival, Russian splits for having one.
- **A page cannot be named after the tab holding it.** Russian spends *Статистика* on the Insights
  tab, having no everyday noun for *insights*, so the page English calls Statistics takes «По
  месяцам» — what it is organised by, which is the one thing about it no other page shares. The
  literal word is not available at any price here: a tab and its own page reading alike would leave
  the reader unable to tell which of the two they had opened.
- **Undo and cancel share a root and must not share a form.** «Отменить» (the verb) is undo — the
  snackbar action reverting what just happened; «Отмена» (the noun) is the dialog button that
  backs out before anything happens. The verb/noun line is the platform's own convention and the
  only thing keeping the two apart.
- **The recorded path is a *маршрут*.** Russian uses the word for a route travelled, not only one
  planned, and the loanword *трек* is GPS jargon a plain reader shouldn't need. The word belongs
  to the track alone: a *поездка* is never called a *маршрут*, which is what keeps the path/event
  line en.md draws.
