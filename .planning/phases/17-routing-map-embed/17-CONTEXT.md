# Phase 17 Context: Routing & Map Embed

## Decisions

### Navigation
- **Claude's Discretion:** React Router implementation (react-router-dom). Header ma linki [Simulation] [Map].
- **Stan symulacji zachowany** po przełączeniu na /map i z powrotem (WebSocket hook + Zustand store persistent).

### Layout strony /map
- **Mapa + panel boczny** — mapa Leaflet po lewej (~75%), panel po prawej (~25%).
- **Panel boczny zmienia zawartość** w zależności od stanu:
  - **Stan 1 (idle):** Przyciski "Fetch Roads" + "Upload Image" (AI fallback)
  - **Stan 2 (loading):** Loader z paskiem postępu (progress bar z procentami)
  - **Stan 3 (result):** Podsumowanie wyniku (X roads, Y intersections) + przyciski "Run Simulation" i "Export JSON"
- **Ciemny motyw** spójny z resztą aplikacji (#0d0d1a background, #e0e0e0 text, monospace font)
- **Mobile:** panel pod mapą (column layout), jak w istniejącym widoku symulacji

### Bounding box
- **Przeciągalny prostokąt** — użytkownik rysuje bbox na mapie przez drag
- Wyświetlenie rozmiaru bbox w metrach w panelu bocznym
- Min/max rozmiar do ustalenia przez implementację

### Zachowanie mapy
- **Domyślna lokalizacja:** Polska, zoom ~13 (miasto)
- **Zapamiętywanie pozycji:** pozycja/zoom w Zustand store, persystentne między przełączeniami stron
- **Brak geocodingu** w tej fazie (wyszukiwarka adresu → future)

## Specifics

- Istniejący App.tsx nie ma routera — trzeba dodać react-router-dom i owrapować App w BrowserRouter
- Istniejące komponenty (SimulationCanvas, ControlsPanel, StatsPanel) przenoszone do route "/"
- Nowa strona /map to nowy komponent MapPage
- useWebSocket hook musi działać na obu stronach (lub lazy — connect on simulation page only)
- Leaflet wymaga CSS import (leaflet/dist/leaflet.css)

## Canonical Refs

- `frontend/src/App.tsx` — obecny layout, punkt integracji routera
- `frontend/src/store/useSimulationStore.ts` — Zustand store do rozszerzenia o map state
- `frontend/src/hooks/useWebSocket.ts` — hook WebSocket, decyzja o lazy loading
- `.planning/research/ARCHITECTURE.md` — decyzje architektoniczne z researchu
- `.planning/research/STACK.md` — stack additions (react-leaflet, react-router-dom)

## Deferred Ideas

- Geocoding (wyszukiwarka adresu na mapie) → future phase
- Zapisywanie ulubionych lokalizacji → future
- Wiele bbox-ów naraz → future
