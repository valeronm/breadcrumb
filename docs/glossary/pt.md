# Portuguese (European)

Answers the concepts in [`en.md`](en.md) with Portuguese words; the strings live in `res/values-pt/`.
What each concept *means* is written there once and not repeated here — this file holds only what is
Portuguese's own: the word, and the reasoning behind it.

## Terms

Sections and order follow `en.md`, so the two files read side by side.

### Core

| Concept | Term | Notes |
|---|---|---|
| timeline | cronologia | |
| trip | viagem | One word where English has several — see decisions below. |
| stay | permanência | Verb form *ficou*. |
| place | local | *Local* is always the app's saved object. The empty states that mean "no place is involved" say *sítio* — "encontrar sítios onde parou", "noutro sítio" — and must never say *local*, which would assert a saved place exists in the very sentence denying it. |

### The timeline

| Concept | Term | Notes |
|---|---|---|
| journey | viagem | |
| night-away | noite fora | |

### A trip

| Concept | Term | Notes |
|---|---|---|
| track | trajeto | Never *percurso*, *rota*, *caminho*. Only the recorded path: everywhere the sentence is about the journey itself, the word is *viagem*. |
| point | ponto | Rejected ones are *ruidosos*; a guessed one is a *posição estimada*, the phrase the setting that drops them already used. |
| activity | atividade | |

### A stay

| Concept | Term | Notes |
|---|---|---|
| stop | paragem curta (a timeline row) · paragem (a halt while recording) | A detected dwell is *permanência detetada* and the Places filter is *Pouco visitados* — neither is a *paragem*. |
| visit | visita | The same event *permanência* names on the timeline; only Places says *visita*. |

### A place

| Concept | Term | Notes |
|---|---|---|
| pin | pino | Only a place's. The add-trip form's coordinates are *pontos*. |
| capture-radius | raio de captura | |
| place-category | categoria | The *Viagem* category shares its word with trip/journey, which is the same collision as everywhere else in this file and needs no third term. |

### Actions

| Concept | Term | Notes |
|---|---|---|
| merge | juntar | *Merged away* is *absorvida* — the stay is absorbed, not joined. |
| split | dividir | |
| delete | eliminar | |
| remove | remover | |
| clear | limpar | |
| undo | anular | |
| restore-trip | recuperar | Matches *recuperáveis* on the same screens. Plain-prose "puts the trip back" (undo explainers) may still say *repõe*. |
| restore-backup | restaurar | |
| reset | repor | |

### The app

| Concept | Term | Notes |
|---|---|---|
| recording | gravação | The recorder's progressive titles use *A gravar*. Never *registo* — that is the logs, in this same group, and the collision cost the notification channel its name once already. |
| positioning | localizar (verb), posicionamento (noun) | Progressive *a localizar* in the recorder's status line; *posicionamento por satélite* where the setting names the technique. |
| backup | cópia de segurança | Short form *cópia*; the verb is *copiar*. |
| search | procurar (verb), pesquisa (noun) | Only the user's. What the receiver does is *posicionamento*. |
| lock | bloquear / desbloquear | |
| logs | registos | |

## Conventions

- European forms always: *telemóvel*, *ecrã*, *ficheiro*, *aplicação* (never *celular*, *tela*,
  *arquivo*, *app*).
- Acordo Ortográfico spelling: *deteção*, *atual*, *ótimo*.
- Progressive is *estar a* + infinitive: *A gravar*, *A importar*, *A restaurar*.
- The user is addressed in the polite third person: *a sua posição*, imperative *Defina*, *Toque*.
- A clock time takes its article: *às 9:00*, *desde as 9:00*, *até às 9:00*.
- Zero counts as singular, so a plural's "one" form carries the number where English can write
  "the one track" (`ResourceHygieneTest` allows the added placeholder for exactly this).

## Decisions

- **trip and journey are both *viagem*, and so is the *Viagem* category.** Portuguese genuinely uses
  one word here, and inventing distinctions the language doesn't make (*deslocação*, *jornada*) would
  read as translationese. The screens keep them apart by context — a trip is a row, a journey heads a
  band in Insights, and the category is a chip in a picker.
- **Portuguese splits what English says with restore and reset**: *restaurar* rebuilds from a file,
  *recuperar* brings a deleted trip back, *repor* is reserved for reset.
- **A screen named after what it holds agrees with it.** "Eliminadas recentemente" is feminine
  because everything on it is a *viagem* — the generic masculine would sit beside "Sem viagens
  eliminadas" unagreed. Every reference to the screen carries the same gender, and if the screen ever
  holds a second noun, the generic masculine becomes right and this entry flips.
