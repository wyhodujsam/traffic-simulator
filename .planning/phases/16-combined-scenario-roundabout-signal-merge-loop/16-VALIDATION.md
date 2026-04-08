---
phase: 16
slug: combined-scenario-roundabout-signal-merge-loop
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-08
---

# Phase 16 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + AssertJ (Spring Boot 3.3.x test starter) |
| **Config file** | `backend/pom.xml` (spring-boot-starter-test managed) |
| **Quick run command** | `cd backend && mvn test -Dtest=MapLoaderScenarioTest -q` |
| **Full suite command** | `cd backend && mvn test -q` |
| **Estimated runtime** | ~15 seconds |

---

## Sampling Rate

- **After every task commit:** Run `cd backend && mvn test -Dtest=MapLoaderScenarioTest -q`
- **After every plan wave:** Run `cd backend && mvn test -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 15 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 16-01-01 | 01 | 1 | MAP-01 | unit | `mvn test -Dtest=MapLoaderScenarioTest#loadsCombinedLoop` | ❌ W0 | ⬜ pending |
| 16-01-02 | 01 | 1 | MAP-01 | unit | `mvn test -Dtest=MapLoaderScenarioTest` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Add `loadsCombinedLoop()` test method to `MapLoaderScenarioTest.java` — covers MAP-01

*Existing infrastructure covers framework and config. Only one new test method needed.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Vehicles visually circulate on loop road | MAP-01 | Visual rendering in browser | Start app, load combined-loop scenario, observe vehicles entering loop and circulating back |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 15s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
