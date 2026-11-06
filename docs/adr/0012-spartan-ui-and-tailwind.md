# ADR-0012: spartan/ui and Tailwind for the web UI

- Status: Accepted (decided 2026-09-26)
- Date: 2026-09-26
- Sources: CONVENTIONS §3, §11

## Context

- The web app is Angular 22: standalone components, zoneless, signals, `httpResource`.
- The UI is live and event-heavy: transcripts, boards, org charts.
- The UI kit must fit signals and zoneless change detection, and its licence must allow commercial use.

## Decision

- **UI kit:** spartan/ui 1.x.
  - Primitives come from `@spartan-ng/brain`.
  - Styled components are generated with the spartan CLI into `web/src/app/ui/`. They belong to us, with spartan/ui's MIT notice beside them (`web/src/app/ui/LICENSE`).
- **Styling:** Tailwind 4. Light and dark themes use a class on `<html>`.
- **Angular CDK** for drag-drop and virtual scroll.
- **Companion libraries**: TanStack Table (the spartan data table), AG Grid Community for large virtualized tables, ECharts (`ngx-echarts`), TipTap for markdown, CodeMirror 6 for code and diffs, Foblex Flow for the org chart and flows.

## Consequences

- The licence is MIT.
- The components are built for signals and zoneless change detection.
- The styled components live in our repository. We can change them freely, but upstream fixes do not arrive automatically.
- Tailwind is part of the web build.

## Alternatives considered

- **Angular Material 3 with the CDK.** Kept as the fallback.
- **PrimeNG.** Excluded: from version 22, first published as a release candidate on 2026-06-28, its npm package is under PrimeTek's PrimeUI License, not MIT. It ships as a compiled package whose source may not be extracted, needs a licence key, and is free only for small organisations, individuals, non-profits and non-commercial open-source projects; other organisations need a paid licence. Up to 21.x its community versions are MIT (`LICENSE.md` in each version's package).
