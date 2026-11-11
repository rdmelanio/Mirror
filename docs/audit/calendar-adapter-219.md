# eCrew direct calendar adapter — 1.12.14 / 219

Baseline fetched before edits: 75142ad2be41759a5bbd0fe16bddb5b3efa484d1 (build-77). Both user-provided ZIPs were inspected privately. The raw ZIPs, HTML, event records, identity and dates remain outside Git and public test fixtures.

## Evidence and implemented mapping

The localStorage snapshots have empty CrewInformation and empty timing objects. The captured CrewSchedule HTML instead contains an inline JSON Events array and authoritative page assignments for CrewInformation, PeriodStart, PeriodEnd and PublishEnd. The page's time selector explicitly maps 1 to Local Station, 2 to UTC and 3 to Local Base (MNL). The adapter accepts the observed Local Station mode and rejects unsupported modes rather than guessing. It reads JSON literals without executing page code.

Events expose start_date/end_date, type, resource, report/debrief, text, details and IsDeadhead. Their legs arrays are empty; sector records are present in details. The parser reads every matching sector, A/E/S time-kind markers and superscript next-day offsets. It validates airport timezones and UTC ordering. Standby events missing separate report/debrief fields must have an explicit details interval agreeing with their calendar timestamps. Hotel/rest events retain their spans without inventing alarm report instants. Blank days are never inferred as OFF.

The observed A row is the current own-roster calendar, not a separately proven published snapshot. Provenance is recorded as current-calendar; publication coverage is bounded by the page's PublishEnd. Unsupported row combinations, ambiguous crew identity, unsupported time modes, incomplete coverage or contradictory sector/timing evidence reject the source. No schedule confirmation, message update, logout, print or guessed endpoint is triggered by the adapter.

Multi-day duty/rest intervals may establish coverage of continuation days; they do not create synthetic OFF records or duplicate flight alarms. The validation boundary requires both supplied coverage and corresponding explicit duty spans. Existing same-crew merge and wrong-crew guards remain active.

## Native flow

The existing origin/tab/session-scoped Gecko network capture supplies CrewSchedule HTML. A snapshot arriving before automatic acquisition begins is kept only in the owning browser's memory, consumed once and checked against its acquisition epoch. Validated data goes to RosterStore.acceptStructured, which commits JSON transactionally, preserves latest/previous PDFs and refreshes the existing Clock/alarm/notice paths. Only a successful store acceptance completes the structured acquisition successfully. Reload, termination and new acquisition epochs reject stale pending results.

This is source integration; it is not proof that the operator's actual phone has completed login→roster→Clock/alarms. Real login/frame races, publication/pending-change behavior, unknown airports and full duty accuracy require device comparison. A bounded DOM script snapshot covers missed network capture without executing page code or reading login inputs. Each actual document is delivered once. Explicit refresh after a successful import reloads the read-only view to acquire fresh data; old cached scripts are not replayed. AJAX-only responses without a new document remain outside the adapter.

## Tests and privacy

EcrewCalendarParserTest uses synthetic OFF, standby and four-leg overnight records; it exercises direct completion versus raw capture and cancellation. An optional private HTML input verifies the actual supplied captured layout without emitting its values. Public runs use a synthetic equivalent. The original source adapter was first tested against the private capture and passed after adding the observed explicit standby-interval variant.

No APK signing changes, data clear or credential migration is part of this change. Prerelease publication follows the owner’s earlier GitHub instruction; stable release selection is preserved. The next compatible version is 1.12.14 / 219 because 1.12.13 / 218 is already released as build-77. Detailed final validation is recorded after gates complete.

## Additional boundary checks

DST folds/gaps reject rather than choosing an implicit UTC offset. The bridge limits snapshots to CrewSchedule documents, one MiB, and a single delivery per document; paused capture is blocked. EcrewCalendarParserTest covers completion versus raw capture/cancellation; extension tests cover non-execution, no clicks, route restrictions, pause and size bounds. These deterministic tests do not replace real Gecko/Android device tests.

## Final local validation

`MIRROR_PRIVATE_CALENDAR_HTML=/workspace/work/private-roster-verify/latest-schedule.html /workspace/work/gradle-calendar.sh :app:testLiteDebugUnitTest :app:testFullDebugUnitTest :app:lintLiteRelease :app:lintFullRelease :app:assembleLiteRelease :app:assembleFullRelease` passed (3m15s). The helper supplies cached JDK/SDK/Gradle locations, --offline, --max-workers=2 and --console=plain.

- Lite: 353 tests, zero failures/errors/skips. Full: 363 tests, zero failures/errors/skips.
- Synthetic calendar parser run without the private input passed independently.
- 39 extension tests passed; metadata/version, theme and release flavor checks passed.
- Lint: lite 0 errors / 229 warnings; full 0 errors / 264 warnings.
- Both release APKs verified as com.mirror.app / 1.12.14 / 219, with the existing certificate; native/Gecko/models remain full-only arm64.
- The initial high-parallelism full run hit an EOF in MjpegServerTest. The unchanged HTTP tests passed in isolation, followed by the complete passing run above. No timeout or assertion was weakened; the exact cause of that transient failure is unproven.
- Full Android source→RosterStore→Clock/alarm acceptance remains a physical-device gate. Supported capture parsing is verified; no real phone success or performance gain is claimed.

## Phone acceptance

Install over build-77 without clearing app data. Open eCrew, let saved login fill, and tap Log In manually. Expect My Schedule navigation, a structured import log category ROSTER_COMMITTED and Roster updated only after acceptance; no Print/PDF viewer. Compare the next duty, all flight legs and station-local report/release times, standby/rest spans and alarms to the portal. Check a repeated explicit refresh obtains a fresh view, and pause/back/logout/reconnect cannot commit an old acquisition. Failed/partial/wrong-crew/unknown-zone input must retain the previous roster. Time modes other than Local Station require a fresh reload after switching. Verify overnight/DST behavior and ordinary session expiration preserves remembered login and offline roster. Earlier phone/TCL controls and upgrade gates remain in device-validation-218.md.
