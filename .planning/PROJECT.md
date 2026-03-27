# Traffic Simulator

## What This Is

Aplikacja webowa symulujaca ruch uliczny w widoku z gory (top-down). Samochody przedstawione jako prostokaty poruszaja sie po wielopasmowych drogach, przyspieszajac i zwalniajac z indywidualnymi parametrami fizycznymi. Celem jest badanie powstawania korkow — phantom traffic jams, propagacja fali hamowania, wplyw przeszkod i zwezen na plynnosc ruchu. Stack: Java 17 + Spring Boot (backend), React + TypeScript (frontend), WebSocket.

## Core Value

Wierna symulacja fizyki ruchu drogowego — samochody realistycznie przyspieszaja, hamuja i reaguja na otoczenie, umozliwiajac obserwacje emergentnych zjawisk korkowych.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Silnik symulacji tick-based z fizyka przyspieszania/hamowania
- [ ] Samochody z indywidualnymi parametrami (max predkosc, przyspieszenie, hamowanie)
- [ ] Car-following model — jazda w odstepach, reagowanie na pojazd przed soba
- [ ] Wielopasmowe drogi ze zmiana pasow
- [ ] Skrzyzowania z sygnalizacja swietlna
- [ ] Ronda z regulami wjazdu/wyjazdu
- [ ] Pierwszenstwo z prawej strony
- [ ] Przeszkody blokujace caly pas (dodawanie/usuwanie w trakcie)
- [ ] Zwezenia drog (redukcja liczby pasow)
- [ ] Widok z gory — Canvas rendering, samochody jako prostokaty
- [ ] Kontrolki: start/stop/pauza, predkosc symulacji
- [ ] Kontrolki: spawn rate, max predkosc samochodow
- [ ] Kontrolki: dodawanie/usuwanie przeszkod na zywo
- [ ] Kontrolki: zmiana cykli swiatel
- [ ] Panel statystyk: srednia predkosc, gestosc, przepustowosc
- [ ] WebSocket real-time komunikacja backend-frontend
- [ ] Predefiniowane scenariusze map
- [ ] Wczytywanie konfiguracji map z JSON

### Out of Scope

- Edytor map (rysowanie drog) — v2, po walidacji podstawowej symulacji
- Heatmapa korkow — v2, po walidacji statystyk
- AI/ML sterowanie pojazdami — zbyt zlozony, nie jest celem
- Persystencja/baza danych — symulacja jest stanowa in-memory
- Autentykacja uzytkownikow — nie potrzebna, single-user app

## Context

- Projekt inspirowany badaniami phantom traffic jams (Nagel-Schreckenberg model, IDM)
- Car-following model: Intelligent Driver Model (IDM) lub uproszczona wersja
- Symulacja tick-based: backend liczy stan co tick, wysyla snapshot przez WebSocket
- Mapy definiowane jako JSON — drogi, pasy, skrzyzowania, ronda, polaczenia
- Frontend renderuje stan na HTML5 Canvas
- Opis pomyslu w bazie wiedzy: ~/mkdocs-kb/docs/pomysly/dev/symulacja-ruchu-ulicznego.md

## Constraints

- **Tech stack**: Java 17 + Spring Boot 3.x (backend), React 18 + TypeScript + Vite (frontend) — preferencja uzytkownika
- **Komunikacja**: WebSocket (STOMP over SockJS) — real-time wymagane
- **Rendering**: HTML5 Canvas — wydajnosc dla setek pojazdow
- **Build**: Maven (backend), Vite (frontend) — standardowy tooling

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Tick-based simulation on backend | Deterministyczna symulacja, frontend tylko renderuje | — Pending |
| Canvas zamiast SVG | Wydajnosc przy duzej liczbie pojazdow | — Pending |
| WebSocket (STOMP) | Real-time push stanu symulacji | — Pending |
| JSON config map | Elastycznosc, latwiej niz edytor map na start | — Pending |
| Przeszkody blokuja caly pas | Prostsza logika, wyraznie widoczny efekt na ruch | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-03-27 after initialization*
