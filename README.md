# brasi-nova-task-management

Task-Management-Service im brasi-nova Oekosystem (KSF-Consumer, Java/Spring Boot, MSSQL).

Mandantenfaehiger Task-Service: benutzer-scoped Inbox, persoenliche Marker, Kommentare,
Notifications, globaler Tag-Katalog, WebSocket-Push, Due-Reminder.

Phase 1a (Core-Service) wird aktuell aufgebaut. Details/Architektur im Office-Brain
(Partition `brasi-nova-task-management`).

## Customer als Task-Quelle (vorbereitet, nicht aktiviert)

`POST /api/v1/tasks` akzeptiert optional `source: {module: "application",
entityType: "customer", entityRef: UUID}`. Der Customer-Adapter wird nur mit
`task.source.customer.enabled=true` als Bean registriert; ohne diese Eigenschaft
bleibt der Typ unregistriert. Er prueft den aktuellen Benutzer-Bearer gegen
Applications `GET /api/v1/customers/{tenantId}/{customerId}/task-source-validation`.
`source.href` bleibt `null`, bis ein beim Oeffnen gleichwertig geschuetzter
Customer-Detailpfad existiert.

Die Werte fuer die folgenden Eigenschaften muessen **je Laufzeitprofil aus
kpl-config** kommen. `application-base-url` ist nur die interne Origin
(`http[s]://host[:port]`, ohne Pfad); sie muss exakt in `allowed-base-urls`
stehen. Ohne konfigurierte URL und Allowlist scheitert die Initialisierung bei
aktiviertem Adapter. Der Adapter folgt keinen Redirects und sendet den Bearer
nur an diese konfigurierte Origin.

| Profil | `task.source.customer.enabled` | `task.source.customer.application-base-url` | `task.source.customer.allowed-base-urls` |
| --- | --- | --- | --- |
| `dev-backend` | fehlt / `false` | vor Aktivierung aus kpl-config | vor Aktivierung aus kpl-config |
| `dev-ui` | fehlt / `false` | vor Aktivierung aus kpl-config | vor Aktivierung aus kpl-config |
| `prod-alpha` | fehlt / `false` | vor Aktivierung aus kpl-config | vor Aktivierung aus kpl-config |
| `prod` | fehlt / `false` | vor Aktivierung aus kpl-config | vor Aktivierung aus kpl-config |

Aktivierung ist weiterhin gesperrt, bis Application-Gate und Identity-Token
im jeweiligen Profil zusammenpassen, ein gleichwertig geschuetzter Link-Zielpfad
vorliegt und der Idempotenzvertrag umgesetzt ist. Vorschlag fuer V1: Bei
source-Create ist `Idempotency-Key` verpflichtend (1 bis 128 ASCII-Zeichen),
eindeutig pro `(tenant UUID, actor UUID, key)` mit DB-Unique-Constraint und
transaktional gespeicherter Task-UUID plus SHA-256 des kanonischen Create-DTOs.
Erster Request liefert `201`; gleicher Key und gleicher Payload-Hash liefert
`200` mit derselben Task-UUID; gleicher Key mit anderem Hash liefert `409`.
Parallele Requests muessen ueber die DB-Unique-Grenze serialisiert werden.
Dieser Vertrag ist noch nicht implementiert; das Feature-Flag bleibt deshalb aus.
