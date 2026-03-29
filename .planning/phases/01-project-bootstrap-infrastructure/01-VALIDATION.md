---
phase: 1
slug: project-bootstrap-infrastructure
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-27
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (backend), Vitest (frontend) |
| **Config file** | backend: pom.xml, frontend: vitest.config.ts |
| **Quick run command** | `cd backend && mvn test -q` / `cd frontend && npx vitest run --reporter=verbose` |
| **Full suite command** | `cd backend && mvn test && cd ../frontend && npx vitest run` |
| **Estimated runtime** | ~15 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick test command for affected module
- **After every plan wave:** Run full suite command
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 15 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 1-01-01 | 01 | 1 | INFR-01 | build | `cd backend && mvn compile -q` | ❌ W0 | ⬜ pending |
| 1-02-01 | 02 | 1 | INFR-02 | build | `cd frontend && npm run build` | ❌ W0 | ⬜ pending |
| 1-03-01 | 03 | 1 | INFR-03 | integration | `cd backend && mvn test -Dtest=WebSocketConfigTest` | ❌ W0 | ⬜ pending |
| 1-04-01 | 04 | 2 | INFR-03 | unit | `cd frontend && npx vitest run useWebSocket` | ❌ W0 | ⬜ pending |
| 1-05-01 | 05 | 2 | SIM-08 | unit | `cd frontend && npx vitest run useSimulationStore` | ❌ W0 | ⬜ pending |
| 1-06-01 | 06 | 3 | SIM-08 | e2e | Backend tick → WebSocket → frontend console log | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/.../WebSocketConfigTest.java` — stubs for INFR-03
- [ ] `frontend/src/__tests__/useWebSocket.test.ts` — stubs for STOMP hook
- [ ] `frontend/vitest.config.ts` — Vitest config with test setup

*Existing infrastructure covers all phase requirements after Wave 0 setup.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| WebSocket latency < 100ms | SIM-08 | Requires running backend + frontend simultaneously | Start backend, start frontend, open browser console, verify tick messages arrive at ~50ms intervals |
| CORS works on Vite dev port | INFR-03 | Network configuration | Verify frontend at :5173 connects to backend at :8080 via Vite proxy |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 15s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
