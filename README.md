# brasi-nova-task-management

Task-Management-Service im brasi-nova Oekosystem (KSF-Consumer, Java/Spring Boot, MSSQL).

Mandantenfaehiger Task-Service: benutzer-scoped Inbox, persoenliche Marker, Kommentare,
Notifications, globaler Tag-Katalog, WebSocket-Push, Due-Reminder.

Phase 1a (Core-Service) wird aktuell aufgebaut. Details/Architektur im Office-Brain
(Partition `brasi-nova-task-management`).

## Customer als Task-Quelle (vorbereitet, nicht aktiviert)

`POST /api/v1/tasks` bleibt die manuelle Anlage mit `TASK_WRITE` und lehnt `source` ab.
`POST /api/v1/tasks/from-source` verlangt `TASK_CREATE`, `Idempotency-Key` und
`source: {module: "application", entityType: "customer", entityRef: UUID}`.
`TASK_CREATE` erlaubt die initiale Zuweisung anderer aktiver Benutzer desselben
Mandanten nach den bestehenden Create-Validierungen (maximal 100 Assignees/Tags).
Der Customer-Adapter wird nur mit
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

## Contact als Task-Quelle (lokaler Kandidat, nicht aktiviert)

Der separate Adapter wird nur mit `task.source.contact.enabled=true` registriert
und prueft `GET /api/v1/contacts/{tenantId}/{contactId}/task-source-validation`
mit dem aktuellen Benutzer-Bearer. `application:contact` bleibt in allen
Profilen ohne explizite kpl-config-Aktivierung unregistriert. Die Eigenschaften
`task.source.contact.application-base-url` und
`task.source.contact.allowed-base-urls` muessen wie beim Customer aus dem
jeweiligen Profil stammen. `source.href` bleibt `null`.

## Employee als Task-Quelle (lokaler Kandidat, nicht aktiviert)

Der separate Adapter wird nur mit `task.source.employee.enabled=true` registriert
und prueft `GET /api/v1/employees/{tenantId}/{employeeId}/task-source-validation`
mit dem aktuellen Benutzer-Bearer. `application:employee` bleibt ohne explizite
kpl-config-Aktivierung unregistriert. Die Eigenschaften
`task.source.employee.application-base-url` und
`task.source.employee.allowed-base-urls` muessen wie beim Customer aus dem
jeweiligen Profil stammen. `source.href` bleibt `null`.

## Firm als Task-Quelle (lokaler Kandidat, nicht aktiviert)

Der separate Adapter wird nur mit `task.source.firm.enabled=true` registriert.
Er akzeptiert als `entityRef` ausschliesslich die UUID des aktiven Mandanten und
prueft `GET /api/v1/firms/{tenantId}/{firmId}/task-source-validation` mit dem
aktuellen Benutzer-Bearer. `application:firm` bleibt ohne explizite
kpl-config-Aktivierung unregistriert. Die Eigenschaften
`task.source.firm.application-base-url` und
`task.source.firm.allowed-base-urls` muessen wie beim Customer aus dem
jeweiligen Profil stammen. `source.href` bleibt `null`.

Die Flags fuer Customer, Contact, Employee und Firm bleiben in `dev-backend`,
`dev-ui`, `prod-alpha` und `prod` fehlend beziehungsweise `false`, bis die
jeweilige Profil-Aktivierung mit einem realen Benutzer-Token geprueft ist.

Source-Create verlangt einen Key mit 1 bis 128 sichtbaren ASCII-Zeichen. Die
Receipt-Tabelle erzwingt `UNIQUE(tenant UUID, actor UUID, key)` mit binaerer
Schluessel-Kollation. Receipt, Task, Teilnehmer, User-State und Create-Effekte
werden in einer Transaktion gespeichert. Der SHA-256-Fingerprint kodiert in
Version 1 den effektiven Titel (gestrippt), Beschreibung, Prioritaet (Default
`NORMAL`, auf den Dictionary-CODE aufgeloest), Start-/Faelligkeitsdatum,
sortierte deduplizierte Assignees (ohne Creator) und Tags, Marker (Default
`false`) sowie das Source-Tupel. Erster
Request liefert `201`; gleicher Key und Fingerprint liefert nach erneuter
Source-Read-Pruefung des gespeicherten Task-Quellkontexts und aktiver
PERSON-Membership-Pruefung `200` mit derselben UUID;
anderer Fingerprint liefert bei fortbestehendem Zugriff `409`. Ausgetretene
oder inaktive Teilnehmer und inaktive Tasks koennen per Replay keine
gespeicherte ID lesen.
Die vier technischen Priority-Codes sind V1-Seedwerte. Werden Dictionary-Eintraege
spaeter deaktiviert oder umbenannt, kann ein historischer Replay bei der erneuten
Code-Aufloesung mit 400 scheitern; solche Seed-Aenderungen brauchen eine eigene
Receipt-Migration.

Vor Profil-Aktivierung muessen `TASK_CREATE` als bare Action-Key in der
`task-management`-Modulregistrierung von kpl-config/Identity bekannt sein und
ein realer Profil-Token nach explizitem Grant `task-management:TASK_CREATE`
tragen. Ein `TASK_WRITE`-Token allein darf den Kontext-Endpunkt nicht bedienen;
ein reiner `TASK_CREATE`-Token darf die manuelle Route nicht bedienen. Customer-
Read-Gate, Identity-Token und ein gleichwertig geschuetzter Link-Zielpfad sind
weitere Profil-Gates. Das Customer-Feature-Flag bleibt bis zu diesen Proben aus.

## PR-Validierung und MSSQL-Gate

Der PR-Workflow `Validate Task Management PR` prueft den Merge-Stand gegen
`developer`: Java-Formatierung der im PR geaenderten Runtime-Dateien mit JDK 21
sowie Build und Unit-Tests mit JDK 25 (Job `validate`) und alle
Integrationstests gegen einen MSSQL-Container (Jobs `integration-test-shards`
und `integration-tests`, siehe unten). Er braucht das Repository-Secret
`KSF_PACKAGES_READ_TOKEN` fuer die KSF-Abhaengigkeiten. Ein fehlendes Secret
laesst die Pruefung fehlschlagen. Der Workflow baut oder veroeffentlicht keine
Snapshot-Release.

Der Job `validate` allein ist **kein** MSSQL-Integrationsnachweis; den liefert
der Check `Integration tests`. Vor der
Integration des Task-Source-Branches muss auf dem finalen Code-Stand der
`TaskFacadeControllerIT` mit echtem MSSQL und den lokalen
`BRASI_INT_DB_USER`/`BRASI_INT_DB_PASSWORD` (optional `BRASI_INT_DB_URL`)
ausgefuehrt werden:

```powershell
mvn -pl brasi-nova-task-management-rt -am '-Dtest=TaskSourceRegistryTest,TaskSourceCreateServiceTest,CustomerTaskSourceAdapterTest,ContactTaskSourceAdapterTest,EmployeeTaskSourceAdapterTest,FirmTaskSourceAdapterTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TaskFacadeControllerIT' verify
```

Der native Maven-Exit muss `0` sein; im Failsafe-XML fuer
`TaskFacadeControllerIT` muessen Tests ausgefuehrt worden sein und `failures`,
`errors` und `skipped` jeweils `0` sein. Der Test erstellt und entfernt seine
eigene MSSQL-Wegwerf-Datenbank. Ohne DB-Zugang wird der IT uebersprungen und
erfuellt dieses Gate nicht.

### Integrationstests in der CI

Die `*IT`-Klassen (Failsafe) laufen im PR-Workflow parallel in Shards, je Shard
gegen einen eigenen MSSQL-Container:

- **Container:** `mcr.microsoft.com/mssql/server:2022-latest` (Variable
  `MSSQL_IMAGE` im Workflow), Edition Developer, Collation
  `SQL_Latin1_General_CP1_CI_AS` und Zeitzone `Europe/Berlin` wie der
  Entwicklungs-SQL-Server. Das SA-Passwort wird je Job zufaellig erzeugt und
  maskiert; es wird nicht gespeichert. Die ITs lesen `BRASI_INT_DB_USER`,
  `BRASI_INT_DB_PASSWORD` und `BRASI_INT_DB_URL` (`RefSchemaItEnv`).
- **Zeitzone:** Audit-Zeiten sind lokale Wanduhr (`app.time.persistence-zone`).
  JVM, Container und Persistenz-Zone muessen dieselbe Zone haben; der Schritt
  `Verify MSSQL is ready and time zones match` bricht bei Abweichung ab.
- **Aufteilung:** `scripts/ci/split-integration-tests.sh <shard> <anzahl>`
  ermittelt die IT-Klassen aus den Test-Quellen und verteilt sie deterministisch
  nach Laufzeit-Gewicht (`scripts/ci/integration-test-weights.txt`). Mit `--plan`
  zeigt es die Verteilung. Neue ITs werden automatisch aufgenommen
  (Standardgewicht); die Zusammenfassung des Gate-Laufs gibt die gemessenen
  Zeiten im Format der Gewichtsdatei aus. Die Shard-Anzahl ist die Laenge der
  Matrix `shard` im Workflow.
- **Auswahl:** je Shard
  `mvn -pl brasi-nova-task-management-rt -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<Klassen>`
  (keine Unit-Tests, nur die ITs des Shards).
- **Gate:** `scripts/ci/check-integration-results.py` scheitert bei 0 Tests, bei
  Failures/Errors, bei **Skips** (Maven bleibt bei uebersprungenen ITs gruen,
  etwa ohne DB-Zugang), bei fehlendem Bericht einer erwarteten IT-Klasse und bei
  Berichten unerwarteter Klassen. Aus den Quellen ermittelt es ausserdem die
  Klassen mit direkten Testmethoden (aeussere und innere `@Nested`-Klassen); jede
  braucht berichtete Testfaelle. Hintergrund: Failsafe schreibt die Testfaelle
  einer aeusseren Klasse mit innerer Testklasse in den Bericht der inneren Klasse
  (`FlywayMigrationIT` meldet selbst `tests="0"`). Der Job `integration-tests`
  fasst alle Shards zusammen und prueft, dass jede IT-Klasse des Repositories
  gelaufen ist. Er ist der Pflicht-Check (`Integration tests`). Tests fuer
  Aufteilung und Gate: `python3 -m unittest discover -s scripts/ci -p 'test_*.py'`
  (laufen auch in diesem Job).
- **Berichte:** Failsafe-Berichte und MSSQL-Log je Shard als Artefakt
  `failsafe-reports-shard-<n>`.
