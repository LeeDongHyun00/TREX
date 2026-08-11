# Good Morning production-domain Gold protocol (draft)

## Status and decision boundary

This document is a **draft, plan-only, non-authoritative protocol** for collecting and
adjudicating production-domain Gold evidence for the Good Morning condition whose canonical AI
Hub text is `무릎 구부린채 고정` (display text may insert spacing, but spacing does not change the
identity below). It is not an approved collection protocol, Gold artifact, calibration artifact,
runtime specification, or release decision.

The current in-memory Good Morning observation trace is an **unlabeled candidate signal**. It has
no independent component labels, phase Gold, synchronized reference, reviewer adjudication, or
approved calibration provenance. It must not be counted, copied, or described as Gold.

At the time of this draft, every authority axis remains exactly zero:

| Authority | Value |
|---|---:|
| Real participant capture | 0 |
| Determinate Gold | 0 |
| Calibration or threshold | 0 |
| Phase provider or decoder | 0 |
| Shadow execution | 0 |
| Runtime provider | 0 |
| User PASS/FAIL/UNKNOWN | 0 |
| Repetition count or score | 0 |
| Real-time cue | 0 |
| Release | 0 |

Passing a document check, schema test, hash check, synthetic fixture, or future Gold annotation
gate cannot increase any of these values implicitly.

## Exact policy binding

Every plan, restricted record, adjudication, split seal, and aggregate receipt must bind the
following exact five-tuple without aliasing or partial matching:

```json
{
  "exerciseId": "good-morning",
  "sourceConditionId": "aihub-exact-sha256-621f2eb88568c0d247abce9bbdbc763e8e40ae396bd0ba254a77dcd8bbc0394d",
  "bindingId": "aihub-binding-sha256-f900f3dc681053ed9b705e020bac0ed27336aa5776885406a3c07a6db67d453d",
  "bindingPolicySha256": "05125e36ac4ebc448120f9d3cc29cbc8837585cde36bc600231a4f30935080e0",
  "policyRegistrySha256": "4cda3be23fe34f9b1f0db1a23e301542c4fecda911a402156877cb4263cc04fc"
}
```

The bound interpretation is `trex.measurement.knee-flexion-angle-stability.v1`, phase role
`trex.phase-role.full-cycle.v1`, side policy `BILATERAL_INDEPENDENT`, and qualified view
`trex.view.lateral-full-body.v1`. LEFT and RIGHT remain independent; a bilateral average, best
side, worst side, mirrored substitute, or silently missing side is not equivalent. Any change to
the five-tuple or these contracts invalidates evidence under this draft and requires a new reviewed
protocol version.

## Gold units and states

The atomic adjudicable key is:

```text
(participantPseudonym, sessionId, deviceInstanceId, cameraGeometryEpoch,
 cycleOrdinal, anatomicalSide, componentId)
```

`anatomicalSide` is exactly `LEFT` or `RIGHT`. `componentId` is exactly `FLEXION` or
`STABILITY`. Therefore one complete cycle has four independent component units:
`LEFT×FLEXION`, `LEFT×STABILITY`, `RIGHT×FLEXION`, and `RIGHT×STABILITY`.

Each component unit has exactly one adjudicated state:

- `CONDITION_SATISFIED`: the independently approved component rubric is satisfied.
- `CONDITION_VIOLATED`: the independently approved component rubric is violated.
- `UNKNOWN_GOLD`: evidence exists but is incomplete, ambiguous, conflicting, outside the
  uncertainty margin, or otherwise insufficient for a determinate state.
- `NOT_OBSERVABLE`: the predeclared reference modality cannot observe the construct for that unit.

For each anatomical side, the compound state uses conservative four-state logic:

- `CONDITION_SATISFIED` only when both FLEXION and STABILITY are satisfied.
- `CONDITION_VIOLATED` only when both components are determinate and at least one is violated.
- `UNKNOWN_GOLD` when either component is `UNKNOWN_GOLD`, even if the other is violated.
- `NOT_OBSERVABLE` is preserved at component level and maps the compound to `UNKNOWN_GOLD` with
  the missing-observability reason; it is never converted to satisfied or violated.

Component metrics are not component Gold unless separate component adjudications under this
protocol exist. In particular, fitting FLEXION and STABILITY branches to one compound sequence
label does not validate either component.

## Phase and full-cycle scope

The required ordered topology is:

```text
READY -> DESCENDING -> BOTTOM -> ASCENDING -> READY
```

A completed-cycle scoring interval is half-open: `[startInclusive, endExclusive)`. It begins at
the first adjudicated `READY→DESCENDING` transition and ends at the final adjudicated
`ASCENDING→READY` transition. Setup READY is not part of the scored cycle. No endpoint-only,
middle-phase-only, best-frame, or valid-frame-only subset may replace the full-cycle window.

A contiguous 500 ms qualified READY interval immediately before `startInclusive` supplies the
per-side STABILITY reference angle. It is reference-only and cannot contribute scored frames. If
500 ms of qualified reference is unavailable, the STABILITY unit is `UNKNOWN_GOLD`.

Reviewers record boundary uncertainty as intervals, not invented point timestamps. If admissible
choices within a boundary interval can change either component state, the affected unit is
`UNKNOWN_GOLD`. An incomplete topology, repeated or missing phase, timestamp regression, gap over
the approved clock contract, person change, or camera-geometry change invalidates the cycle; no
interpolation or neighbouring-cycle borrowing is allowed. AI Hub `active`, sampled ordinals, or a
candidate phase trace are never phase Gold.

## Prospective component rubric

The restricted study plan must reference a separately reviewed, content-addressed SME rubric that
sets numeric `minimumFlexionDegrees`, `maximumDeviationDegrees`, filtering, anatomical axis,
reference uncertainty, and MoCap biomechanical model **before collection and before any outcome
review**. This draft supplies no default cutoff. Missing, unsigned, expired, or post-hoc cutoffs
force `UNKNOWN_GOLD_ONLY` readiness.

For anatomical side `s`, let `theta_s(t)` be the calibrated MoCap knee-flexion angle in degrees and
let `U95_s` be the approved 95% reference-measurement uncertainty. Over the complete scored cycle:

- `F_s` is the fifth percentile of `theta_s(t)` on the predeclared filtered reference timeline.
- `theta_ready_s` is the median over the 500 ms qualified READY reference interval.
- `D_s` is the 95th percentile of `abs(theta_s(t) - theta_ready_s)` over the scored cycle.

The reference-derived component states are fail-closed:

| Component | Satisfied | Violated | Otherwise |
|---|---|---|---|
| FLEXION | `F_s - U95_s >= minimumFlexionDegrees` | `F_s + U95_s < minimumFlexionDegrees` | `UNKNOWN_GOLD` |
| STABILITY | `D_s + U95_s <= maximumDeviationDegrees` | `D_s - U95_s > maximumDeviationDegrees` | `UNKNOWN_GOLD` |

The signed rubric must justify the percentiles, cutoffs, uncertainty model, filter, and anatomical
definition. Neither AI Hub labels nor production MediaPipe values may define or tune them.

## Production observation, MoCap, expert review, and time binding

Each capture group binds immutable identities for the production runtime domain, app build,
observer/model/options, preprocessing, landmark schema, person-lock provider, view qualifier,
source image geometry, crop, rotation, mirroring, device tier and instance, MoCap hardware and
software, anatomical calibration, reference filter, SME rubric, and clock-alignment artifact.
Geometry, person identity, or any bound implementation change starts a new capture group.

Production frames and synchronized MoCap use strictly increasing monotonic timestamps. Clock
alignment is an affine map `t_reference = a * t_device + b`, estimated from independent start and
end synchronization events. It is acceptable only when residual p95 is no greater than the lesser
of 10 ms and half the median production camera-frame interval, and the maximum residual is no
greater than one median camera-frame interval. Failure, unbounded drift, a missing synchronization
event, or an over-contract timestamp gap makes the cycle `UNKNOWN_GOLD`.

Phase reference uses synchronized MoCap trunk/hip motion and blinded expert media review, not the
candidate knee signal. Three qualified experts independently review every adjudicable unit. They
are blinded to AI Hub labels, scripted-fault intent, production output, candidate feature and
threshold, split membership, and one another's submissions until all three are sealed.

All three reviews must be present. A determinate adjudication requires at least two experts to
agree with each other and with the independently derived MoCap reference state. Missing review,
expert/reference conflict, or unresolved boundary disagreement remains `UNKNOWN_GOLD`; an
adjudicator may record rationale but may not infer missing evidence or expose candidate output.

## Prospective split, cohort, and sample adequacy

Split assignment is sealed before outcome review. The assignment unit is the participant: every
session, device, geometry epoch, and capture group belonging to that participant inherits exactly
one of `DEVELOPMENT`, `CALIBRATION`, `LOCKED_INTERNAL_TEST`, or `EXTERNAL_TEST`. Participant,
session, capture, or content duplication across splits is forbidden.

The split manifest must stratify device tier and planned condition while preserving participant
grouping. `EXTERNAL_TEST` uses an independent site and operator and includes at least one
predeclared production device family absent from development and calibration. Locked-test feature,
cutoff, bin, backoff, or policy selection is forbidden.

The cohort contains correct technique, isolated FLEXION violation, isolated STABILITY violation,
both-component violation, and natural-use captures. Reviewers remain blind to instructed cohort.
Deliberate faults require an SME-approved low-risk script, supervision, load limit, and stop rule;
the draft itself does not authorize a participant to perform them.

Before real capture, a participant-clustered power analysis must show at least 0.80 power for all
predeclared acceptance bounds. Hard floors, which power analysis may increase but never decrease,
are:

- at least 30 independent participants per split and required device tier;
- at least 20 independent participants in every `component×state×side×device-tier` cell;
- at least two sessions on separate days for every participant included in an acceptance metric;
- no participant contributing more than 10% of the weight of any reported cell.

An undersized cell is not pooled, relabeled, or rescued with a post-hoc backoff. It is reported as
`CELL_UNDERSAMPLED`, and readiness remains `NOT_READY`.

## Acceptance metrics

Gold annotation acceptance requires all of the following prospectively, for both sides and both
components:

- Krippendorff's alpha at least 0.80 for blinded component reviews;
- phase-boundary pairwise absolute disagreement p95 no greater than one production frame after
  accepted clock alignment;
- the participant-clustered 95% lower confidence bound of adjudicated coverage at least 0.90 in
  every required device-tier cell;
- all sample floors, rights, trust, timing, geometry, MoCap calibration, and review completeness
  gates satisfied.

These gates admit an evidence bundle for a later, separate calibration study; they do not validate
a runtime candidate. A later candidate evaluation must fit feature families, thresholds, quality
margins, and calibration only on DEVELOPMENT/CALIBRATION. On both untouched test splits, each
LEFT/RIGHT component and compound must independently meet participant-clustered 95% lower bounds
of 0.85 for balanced accuracy, 0.80 for sensitivity and specificity, and 0.90 for prediction
coverage. `UNKNOWN` counts as a miss in accuracy, sensitivity, and specificity denominators;
selective accuracy may be reported but cannot satisfy a gate.

## Abstention and invalidation

No missing value is imputed. Each abstention preserves one or more exact reason codes:

- `INCOMPLETE_CYCLE`, `PHASE_BOUNDARY_UNCERTAIN`, `TIMESTAMP_GAP`,
  `CLOCK_ALIGNMENT_FAILED`;
- `MOCAP_DROPOUT`, `MOCAP_CALIBRATION_FAILED`,
  `REFERENCE_UNCERTAINTY_OVERLAPS_CUTOFF`;
- `VIEW_UNQUALIFIED`, `OCCLUSION_OR_MISSING_JOINT`, `PERSON_IDENTITY_CHANGE`,
  `CAMERA_GEOMETRY_CHANGE`;
- `REVIEW_INCOMPLETE`, `EXPERT_REFERENCE_DISAGREEMENT`;
- `RIGHTS_OR_TRUST_UNVERIFIED`, `CELL_UNDERSAMPLED`.

Cycle- or unit-level reasons produce `UNKNOWN_GOLD` or `NOT_OBSERVABLE` as defined above.
Bundle-level rights, trust, signature, split, or sample failures leave the entire bundle
`NOT_READY` and cannot be overridden with a flag.

## Consent, safety, privacy, and restricted evidence

Real capture requires a separately approved rights and ethics package covering informed consent,
purpose, raw video and MoCap use, withdrawal, retention duration, encryption, access control,
backup, deletion, incident response, and any permitted secondary use. Consent and reviewer
identities must be pseudonymous and independently controlled. A missing, expired, revoked, or
unverifiable approval blocks capture or intake.

Restricted evidence follows the existing six-file topology:
`bundle-manifest.json`, `capture-groups.jsonl`, `observations.jsonl`,
`blind-reviews.jsonl`, `adjudications.jsonl`, and `split-manifest.json`. Raw media, production pose
or MoCap trajectories, participant/session/reviewer identifiers, capture timestamps, consent
records, and leaf hashes stay in an access-controlled offline workspace. They must not enter Git,
APK/AAB assets, app storage, logs, crash reports, analytics, telemetry, backups, or a public
receipt. A public readiness artifact may contain only aggregate counts, acceptance results, root
artifact identities, and zero-valued authority fields.

## AI Hub exclusion

Official AI Hub Validation has no role. A conforming implementation records zero path stats, zero
reads, zero hashes, and zero reuse for Gold labels, rubric design, phase boundaries, split
assignment, fitting, calibration, locked testing, or acceptance. It must reject a Validation-named
path before any filesystem probe. AI Hub Training research may be cited only as prior surrogate
rationale; its labels, thresholds, traces, and fitted rules are not production-domain Gold and
cannot populate this protocol's records.

## Minimum future implementation

This draft proposes, but does not create or approve, the following minimum implementation:

1. A self-hashed `good-morning` v2 study-plan artifact containing this exact binding, SME rubric
   root, clock limits, split seal, cohort, sample floors, acceptance gates, and zero authority.
2. A JSON Schema overlay for the six restricted files that requires four component units per cycle,
   exact state/reason enums, half-open boundaries, reference uncertainty, clock provenance, and
   participant-grouped split identity.
3. A fail-closed offline validator that verifies canonical bytes, regular-file snapshots, detached
   signatures against a pinned trust registry, rights, provenance roots, clocks, reviews, splits,
   cell adequacy, metrics, and aggregate-only public output.
4. Synthetic conformance tests for the component AND truth table, LEFT/RIGHT separation,
   component-label independence, boundary uncertainty, timestamp gaps, identity changes,
   expert/reference disagreement, participant leakage, device holdout, undersized cells, stale
   roots, signature or rights failure, and pre-probe AI Hub Validation rejection.
5. A generated aggregate readiness artifact whose current real evidence counts and every authority
   field remain zero until separately approved real evidence passes all gates.

Synthetic fixtures test schema behavior only. They cannot increase participant, reviewer, capture,
Gold, calibration, runtime, shadow, cue, or release counts.

## Promotion gates

Promotion is a sequence of separate, signed, content-addressed decisions; no gate implies the next:

1. Reconfirm the exact binding and approve versioned phase, view, component rubric, and reference
   contracts.
2. Obtain ethics, safety, rights, consent, retention, privacy, and incident-response approval; only
   a separate capture authorization may then change capture authority.
3. Implement and approve protocol-v2 detached-signature verification and a pinned, revocation-aware
   trust registry.
4. Validate the production observer, geometry, person lock, device capability, MoCap calibration,
   and clock alignment before accepting any real record.
5. Seal the participant-grouped split and prospective power/sample plan before outcome review.
6. Validate a signed real restricted bundle and meet Gold agreement, timing, coverage, and sample
   gates; only a separate Gold approval may then admit determinate Gold.
7. Build a separate calibration artifact on DEVELOPMENT/CALIBRATION without touching locked tests.
8. Pass both locked test gates, subgroup and device robustness review, and an independent safety
   review. Gold or calibration success alone still cannot authorize runtime behavior.
9. Approve shadow execution separately, then validate latency, abstention, false-cue risk, and
   fail-safe behavior before any user-facing PASS/FAIL/UNKNOWN, score, cue, or release review.

Until the corresponding signed transition exists, capture, Gold, calibration, shadow, runtime,
phase, score, cue, and release authority remain zero.
