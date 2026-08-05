# Portuguese (European)

Answers the [concept spine](README.md); the strings live in `res/values-pt/`.

## Terms

| Concept | Term | Notes |
|---|---|---|
| track | trajeto | Never *percurso*, *rota*, *caminho*. |
| trip | viagem | One word for three concepts — see decisions below. |
| journey | viagem | |
| travel-category | Viagem | |
| place | local | |
| spot | sítio | The distinction is deliberate: *local* is always the app's object. |
| stay | permanência | Verb form *ficou*. |
| stop | paragem | |
| timeline | cronologia | |
| recording | gravação | The recorder's progressive titles use *A gravar*. |
| point | ponto | Rejected ones are *ruidosos*. |
| fix | sinal | Never *fix* — see decisions below. |
| pin | pino | |
| capture-radius | raio de captura | |
| backup | cópia de segurança | Short form *cópia*; the verb is *copiar*. |
| merge | juntar | *Merged away* is *absorvida* — the stay is absorbed, not joined. |
| split | dividir | |
| delete | eliminar | |
| remove | remover | |
| clear | limpar | |
| undo | anular | |
| restore-backup | restaurar | |
| restore-track | recuperar | Matches *recuperáveis* on the same screens. Plain-prose "puts the track back" (undo explainers) may still say *repõe*. |
| reset | repor | |
| search-text | procurar (verb), pesquisa (noun) | |
| search-gps | procura | Distinct from text search: the receiver *procura*, the user *pesquisa*. |
| lock | bloquear / desbloquear | |
| visit | visita | |
| night-away | noite fora | |
| activity | atividade | |
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

- **trip, journey and travel are all *viagem*.** Portuguese genuinely uses one word for all three,
  and inventing distinctions the language doesn't make (*deslocação*, *jornada*) would read as
  translationese. The screens keep the three apart by context.
- **"fix" is rendered as *sinal*.** There is no natural Portuguese word for a GPS fix; *sinal* is
  what a user would say, and it is used consistently even where English distinguishes "fix" from
  "signal".
- **Three verbs where English has restore/reset**: *restaurar* rebuilds from a file, *recuperar*
  brings a deleted track back, *repor* is reserved for reset.
