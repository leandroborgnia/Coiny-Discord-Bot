# Specification Quality Checklist: Game Queue & Weekly Rotation

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-10
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Clarify session (2026-06-10) resolved: weekly trigger (automatic scheduled tick); spent-coin
  counter-account (per-server pot); one proposal per member; the "wait N games" re-proposal cooldown;
  proposer-departure; bootstrap instant-pop; default costs (1 propose / 1 per bump); recognized-game
  requirement; rich queue view (current + next 5 + own); per-slot upvotes via buttons (idempotent
  across messages); and downtime catch-up.
- The "wait N games" cooldown (FR-011/FR-012) is the subtlest rule; SC-005/SC-006 and the Edge Cases
  pin its observable behavior (fixed at pop; counts only games actually played; empty weeks excluded).
- Second clarify session (2026-06-10) resolved: game identity captured from the proposer's **Discord
  Rich Presence** (must be playing it; full snapshot stored; no catalog/typed title); withdraw + full
  refund; replace-game (free, keeps position, resets the slot's upvotes); no-activity guard (no action,
  no charge, advise checking activity-sharing); opt-in weekly **announcement channel**; and the weekly
  cadence as a **rolling 7 days from the last pop**.
- Some platform-capability terms are intrinsic to the feature and named at the WHAT level (key art,
  interactive buttons vs emoji reactions, Rich Presence activity, an announcement channel). Concrete
  services/libraries (the presence gateway intent, button/art APIs, any cover-art enrichment source)
  are deferred to plan.md and referenced only as examples, so the spec stays implementation-agnostic.
- Third clarify session (2026-06-10) resolved the view/upvote-count surfaces: the queue view is
  **ephemeral/private** (upvote buttons there); ephemeral views and all older messages show upvote
  counts as a **snapshot** and are never updated. The **single live count surface is the latest
  announcement message** (current game + "up next" 5 with thumbnails/names/counts), edited on every
  upvote change; older announcements are not updated. Multi-activity capture takes the first game-type
  activity.
- Operational note for plan.md: reading Rich Presence requires the presence gateway intent and members
  sharing activity; this is a new external dependency and a privacy consideration to record in the plan.
