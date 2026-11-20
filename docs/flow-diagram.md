# Flow diagrams

`experiment-flow.svg` (next to this file) is a single picture that can be attached to the Jira epic. The Mermaid sources below show the same flows in more detail. They render on GitHub, GitLab and in Confluence with the Mermaid macro.

## 1. Configuration flow (Draft)

```mermaid
flowchart TD
    A([Merchandising manager]) --> B[Create experiment<br/>name, start/end, type, priority]
    B --> V1{Details valid?<br/>all mandatory fields,<br/>end after start}
    V1 -- no --> E1[400 - list of field errors<br/>nothing saved]
    V1 -- yes --> D[(Draft · version 1)]
    D --> C[Edit Draft<br/>split · bucketing identifier · pricing treatment]
    C --> V2{Section valid?}
    V2 -- "split ≠ 100 / control = 0" --> E2[400 split error]
    V2 -- "primary in fallback / duplicates" --> E3[400 bucketing error]
    V2 -- "floor ≥ ceiling / unknown product or fare brand" --> E4[400 guardrail error]
    V2 -- yes --> D2[(Draft · version n+1<br/>field changes recorded)]
    D2 --> C
    D2 --> P[Publish]
    P --> V3{Complete?<br/>split + bucketing + treatment present,<br/>end time in future}
    V3 -- no --> E5[400 - what is missing]
    V3 -- yes --> S[(Scheduled)]
```

## 2. Lifecycle state machine

```mermaid
stateDiagram-v2
    [*] --> Draft: create
    Draft --> Draft: edit (new version)
    Draft --> Scheduled: publish (full validation)
    Scheduled --> Draft: unpublish (to edit)
    Scheduled --> Running: start time reached (system)
    Scheduled --> Ended: end (cancel)
    Running --> Paused: pause
    Paused --> Running: resume (before end time)
    Running --> Ended: end / end time reached
    Paused --> Ended: end / end time reached
    Ended --> [*]

    note right of Running
      Running lock: any edit -> 409 EXPERIMENT_LOCKED
      only pause, resume, end allowed
    end note
    note right of Paused
      excluded from bucketing feed
      -> all traffic gets the default
    end note
    note right of Ended
      read-only
    end note
```

## 3. Hand-off to the bucketing engine and traceability

```mermaid
sequenceDiagram
    autonumber
    participant MM as Merchandising manager
    participant API as Experiment Config API
    participant SCH as Lifecycle scheduler
    participant REPO as Experiment store (append-only versions)
    participant BE as Bucketing engine (future epic)
    participant AN as Analyst

    MM->>API: POST /experiments (details)
    API->>REPO: save v1 (Draft)
    MM->>API: PUT /experiments/{id} (split, identifier, pricing)
    API->>REPO: save v2 (config v2)
    MM->>API: POST /experiments/{id}/actions/publish
    API->>REPO: save v3 (Scheduled)
    SCH->>API: tick at start time
    API->>REPO: save v4 (Running, user=system)
    BE->>API: GET /bucketing/active
    API-->>BE: experimentId, configVersion, priority, split, primary + fallback, treatment
    Note over BE: assigns customers and stamps<br/>experimentId + configVersion on traffic
    MM->>API: PUT /experiments/{id} (change split)
    API-->>MM: 409 EXPERIMENT_LOCKED
    MM->>API: POST .../actions/pause
    API->>REPO: save v5 (Paused)
    Note over BE: experiment missing from feed<br/>→ default experience
    AN->>API: GET /experiments/{id}/served?at=...
    API-->>AN: configVersion live at that instant
    AN->>API: GET /experiments/{id}/versions
    API-->>AN: who changed what and when
```
