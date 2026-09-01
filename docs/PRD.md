# Product requirements document

## Product summary

Personal Memo is a mobile-first PWA for capturing unstructured Korean notes. It proposes useful structure without requiring the user to learn a note taxonomy first, and turns confirmed action-oriented notes into tasks and reminders while keeping informational notes discoverable through a graph.

## Primary user

The first target is a student who records rough, abbreviated notes on a phone.

Examples:

- `11.25 os과제 제출`
- `교수님이 xv6 과제 다음주 화요일까지래 vm부분 다시보고 깃에 올리기`
- `가상메모리는 시험에 중요하다고 함`
- `그때 말한 거 다음 주쯤 올리기`

The user should not need to decide a folder, tag, object type, or template before saving.

## User problem

Fast capture tools preserve thoughts but make later retrieval and action difficult. Structured tools improve retrieval but impose organization work at capture time. The product should separate these moments:

1. capture now with no format;
2. receive a compact analysis proposal;
3. confirm only what matters;
4. retrieve later through graph, search, tasks, and reminders.

## Core value propositions

### Frictionless capture

- One primary action saves a memo.
- Original text is persisted before any AI dependency.
- The UI never requires a tag before save.

### Assisted, reversible organization

- The system suggests type, tags, dates, actions, and relations.
- Ambiguous fields are highlighted independently.
- The user can accept, edit, reject, or postpone analysis.
- Relation proposals show only owner-visible target labels, start unchecked, and become canonical
  only for the explicitly selected source-item/target pairs; unavailable targets fail closed.
- All applied analysis can be undone without deleting the original.

### Graph-first retrieval with separate action views

- The mobile entry screen is a confirmed, bounded MEMO-TAG connection map that makes the owner's
  existing knowledge structure the first visual hierarchy.
- Bottom navigation exposes four views: the connection map; a memo view containing capture, the
  raw-memo list, and search; an agenda view containing tasks and confirmed events; and settings.
- The graph renders a curated neighborhood, not the entire corpus, and never treats an unapproved
  analysis proposal as a confirmed connection.
- System types such as TASK and INFORMATION are represented by filters and node styling, not giant hub nodes.
- Lists, search, tasks, and schedules remain usable without understanding graph mechanics.
- Search can reveal and open a memo outside the bounded graph home.

### Knowledge that becomes action

- Confirmed task-like facets produce task records.
- Due dates and completion state affect visual treatment.
- Overdue tasks remain prominent and are never hidden merely because they are old.

### Gradually evolving organization

- Early usage starts with loose notes and a few canonical tags.
- Repeated patterns become provisional topic clusters.
- Stable clusters can be proposed as tags.
- Similar tags can be proposed for merge; broad tags can be proposed for split.
- Semantic changes remain user-confirmed and reversible.

## Main experience

### 1. Sign in and account

- A new user can create an account with email, a 12-character-or-longer password, and a display name.
- A returning user can use local credentials or, when configured, Google sign-in.
- Google never silently claims an existing same-email account. The user signs in through the existing method and explicitly links Google from account settings.
- The account panel shows usable sign-in methods and prevents removal of the final method.
- Signing out returns to the authentication screen and makes owner data inaccessible to the browser session.

### 2. Graph-first mobile home

- Make the confirmed, bounded MEMO-TAG connection map the default signed-in screen.
- Show 50–100 active nodes by default and prioritize recent notes, unfinished tasks, upcoming events,
  pinned items, and important topic tags without inventing new canonical connections.
- Tapping a node highlights its local neighborhood and opens a detail drawer.
- Zooming changes detail level: clusters → tags → individual memos.
- Keep four bottom-navigation destinations: connection map; memo capture, raw-memo list, and search;
  tasks and confirmed events; and settings. Non-graph views must remain usable without graph knowledge.
- Keep detailed recovery, analysis-path, model, and infrastructure diagnostics out of the primary
  visual hierarchy. A compact same-origin reachability indicator may remain as a global utility, but
  it must describe only what the current page can prove and never claim database, Ollama, Cloudflare
  Tunnel, Access, or provider health.

### 2a. Search

- Search is usable without understanding the graph and can open a memo outside the bounded home.
- The current first slice searches the current raw body and latest applied title by literal
  normalized substring, plus canonical tags and aliases by exact normalized equality.
- Lifecycle, task state, derived overdue, and current-revision date filters narrow results without
  making proposal or undone data canonical.
- The query stays in a JSON request body and is not persisted in the URL, browser storage, service
  worker, or ordinary access logs.
- Opening a result re-reads the owner-scoped current raw memo detail and never silently injects it
  into the graph projection.
- Fuzzy/semantic retrieval, cluster reveal, and search-driven graph expansion remain later slices.

### 3. Quick capture

- A dedicated memo view provides quick capture and the current raw-memo list from bottom navigation.
- Saving creates a raw memo immediately.
- Analysis happens after save and must not block further capture.
- The background analysis result remains an untrusted proposal. Canonical tags, tasks, EVENT
  schedules, and relations still require the existing explicit review and Apply action.

### 3a. Owner beta status and update boundary

- Milestone 7.1 remains a historical, source-qualified Today-first checkpoint. Milestone 7.2 is the
  owner-requested graph-first follow-up and changes only the existing UI hierarchy and presentation.
- Milestone 7.2 changes no API, OpenAPI, JSON Schema, Flyway, canonical-data contract, analysis
  producer, or deployment topology. The source-qualified tree was separately deployed through the
  owner-authorized backup, isolated V23-to-V23 restore, connector-first rollback, rebuild, local smoke,
  and connector-last remote boundary sequence. Visual review is in progress; status remains
  `SOLO_PROVISIONAL`/`REPORT_ONLY` rather than production acceptance.
- Connection wording reflects only same-origin API reachability and current workspace state. A green
  status must not be presented as database, model, Tunnel, Access, or provider-wide health.
- PWA refresh remains explicit and blocked by unsaved work or a pending server operation. The UI calls
  it a screen/PWA asset refresh, not a backend, Docker, connector, or operating-system update.
- Analysis-path counts remain lazy, owner-scoped, raw-free, and secondary. They are historical
  aggregate evidence, not real-time LLM availability or model-quality status.
- Model or AI status is not a primary home-screen signal and must not be presented as a quality or
  availability claim.
- The browser application never starts or stops Windows services, Docker, Cloudflare connectors,
  connector metrics, tokens, or Ollama. Those controls stay in the reviewed operator boundary.

### 4. Analysis review

- Open review in a mobile modal rather than expanding the workspace inline.
- First show an exact summary and ask one question: apply this proposal as shown, yes or no.
- Treat yes as explicit approval; no reveals alternative types and then the field-level editor, never proposal rejection.
- If the analyzer returns `UNKNOWN` or different types tie for the top score, skip the yes path and ask the user to choose a supported type.
- Present compact chips for type and tag candidates.
- Show the interpreted date alongside the original date expression.
- Associate a proposed due date with a specific task candidate. If that mapping is absent,
  imprecise, or incompatible after editing, require field-level review instead of guessing by list
  order or candidate count.
- Display any future EVENT temporal alternatives as `아직 미적용`; never initialize a schedule from
  candidate order, score, or model suggestion. A user action must choose an alternative before the
  ordinary Apply confirmation can persist it.
- Keep a bare or date-less `6시` unresolved as `UNKNOWN`; never infer today, AM/PM, an EVENT schedule,
  or an alarm from it.
- Explain only uncertain fields, not the entire model reasoning.
- Allow partial apply.

### 5. Task and event handling

- A memo can produce zero or more task facets.
- MVP should cap automatic extraction to a small number, such as three, to keep review understandable.
- `OVERDUE` is derived from `TODO && dueAt < now`; it is not a separately persisted source status.
- An EVENT gets a canonical timed or all-day schedule only after explicit review. Missing starts,
  ends and durations remain missing; automatic alarm creation is not part of this checkpoint.
- A signed-in owner may preview and download a schedule-only RFC 5545 snapshot. Preview and download
  use the same no-store bytes, and the UI must explain that a downloaded `.ics` is a one-time import,
  not a live subscription, share URL or alarm.
- The separate source-only recipient feed flow defaults to `BUSY_ONLY`, starts with every event
  unselected, and requires explicit membership. It shows a rotate/revoke-capable URL once from
  browser memory and explains that dates/times are still disclosed and recipient copies cannot be
  recalled. V23 adds a separate per-feed, version-pinned public-consent step with a fresh bearer;
  existing and newly created feeds remain `LOCAL_ONLY` until that explicit step succeeds. Actual
  Internet activation and external-client compatibility remain a separate operator decision; no
  feed is an alarm.

### 6. Aging and compression

- Completed tasks and past events may collapse by period or topic.
- Informational notes are not hidden solely because of age.
- Pinned, frequently accessed, highly connected, unfinished, or overdue nodes remain active.
- A cluster is a reversible view; originals remain searchable and expandable.

## Product principles

- AI proposes; the user owns the graph.
- A wrong confident answer is worse than an explicit unknown.
- Preserve spatial and semantic stability as the graph grows.
- Prefer one useful question over a long review form.
- Keep the product usable without graph knowledge through search and task views.
- Prefer progressive automation learned from confirmations over immediate autopilot.

## MVP non-goals

- General team workspace
- Full calendar replacement
- Autonomous life assistant
- Automatic deletion or irreversible merge
- Rich document editor
- Password recovery delivery, public email verification, IP/edge rate limiting and abuse protection, and MFA/passkeys in the first authentication slice
- Voice, image, PDF, and web-clip ingestion
- Automatic whole-corpus ontology generation
- Browser-based Windows service, Docker, Cloudflare connector, token, or host-operation control

## Success signals

- Median time from opening capture to raw memo saved
- Percentage of memos resolved locally
- Analysis proposal acceptance rate
- Field-level correction and rejection rate
- Percentage of memos left unreviewed
- Cloud escalation and failure rate
- Task creation and completion rate
- Search-to-open success rate
- Number of duplicate tag proposals per user
- Undo frequency after analysis application

## Market position

The intended product combines a graph-first view of confirmed knowledge, fast raw capture,
owner-approved organization, separate task and schedule views, and action extraction. The clearest
initial positioning is:

> A student-focused AI second brain that turns rough class notes into connected concepts, assignments, and deadlines.
