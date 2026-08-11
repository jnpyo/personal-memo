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
- All applied analysis can be undone without deleting the original.

### Graph-first retrieval

- The entry screen is a graph of meaningful active topics and memos.
- The graph renders a curated neighborhood, not the entire corpus.
- System types such as TASK and INFORMATION are represented by filters and node styling, not giant hub nodes.
- Search can reveal and expand a memo hidden inside a collapsed cluster.

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

### 2. Graph home

- Show 50–100 active nodes by default.
- Prioritize recent notes, unfinished tasks, upcoming events, pinned items, and important topic tags.
- Tapping a node highlights its local neighborhood and opens a detail drawer.
- Zooming changes detail level: clusters → tags → individual memos.

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

- A persistent input affordance is available from the graph home.
- Saving creates a raw memo immediately.
- Analysis happens after save and must not block further capture.

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
- Explain only uncertain fields, not the entire model reasoning.
- Allow partial apply.

### 5. Task and event handling

- A memo can produce zero or more task facets.
- MVP should cap automatic extraction to a small number, such as three, to keep review understandable.
- `OVERDUE` is derived from `TODO && dueAt < now`; it is not a separately persisted source status.

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

The intended product combines automatic knowledge organization, a graph-first interface, and action extraction. The clearest initial positioning is:

> A student-focused AI second brain that turns rough class notes into connected concepts, assignments, and deadlines.
