# SonarQube - Analiza statyczna kodu

## Srodowisko

- **SonarQube**: Community Edition 26.3.0 (Docker)
- **Obraz**: `sonarqube:community`
- **MCP obraz**: `mcp/sonarqube:latest` (dostepny, ale nie uzywany w tej analizie)
- **Port**: `http://localhost:9000`
- **Kontener**: `sonarqube`

## Dane logowania

- **Login**: `admin`
- **Haslo**: `Admin12345678!`

### Tokeny

| Projekt | Token | Typ |
|---------|-------|-----|
| `traffic-simulator` (backend) | `sqp_89935ee7d67a261ee77ecd2b5f0a3ee13d8d021a` | PROJECT_ANALYSIS_TOKEN |
| `traffic-simulator-frontend` | `sqp_00c494262755473b498f73ec41c98beda88f4259` | PROJECT_ANALYSIS_TOKEN |

## Uruchomienie SonarQube

```bash
# 1. Uruchom kontener (jesli zatrzymany)
docker start sonarqube

# Lub stworz nowy kontener (czysta baza)
docker run -d --name sonarqube -p 9000:9000 sonarqube:community

# 2. Poczekaj na gotownosc (~30s)
curl -s http://localhost:9000/api/system/status
# Oczekiwany wynik: {"status":"UP"}
```

## Uruchomienie analizy

### Backend (Java — Maven plugin)

```bash
cd /home/sebastian/traffic-simulator/backend

mvn clean verify sonar:sonar \
  -Dsonar.projectKey=traffic-simulator \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=sqp_89935ee7d67a261ee77ecd2b5f0a3ee13d8d021a
```

### Frontend (TypeScript — Docker sonar-scanner)

```bash
docker run --rm --network host \
  -v /home/sebastian/traffic-simulator/frontend:/usr/src \
  -w /usr/src \
  sonarsource/sonar-scanner-cli \
  -Dsonar.projectKey=traffic-simulator-frontend \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=sqp_00c494262755473b498f73ec41c98beda88f4259 \
  -Dsonar.sources=src \
  -Dsonar.exclusions="node_modules/**,dist/**,.vite/**,**/*.test.ts,**/*.test.tsx"
```

## Pobieranie wynikow (API)

```bash
# Wszystkie problemy (backend)
curl -s --user admin:Admin12345678! \
  "http://localhost:9000/api/issues/search?componentKeys=traffic-simulator&ps=100"

# Wszystkie problemy (frontend)
curl -s --user admin:Admin12345678! \
  "http://localhost:9000/api/issues/search?componentKeys=traffic-simulator-frontend&ps=100"

# Tylko BLOCKER i CRITICAL
curl -s --user admin:Admin12345678! \
  "http://localhost:9000/api/issues/search?componentKeys=traffic-simulator&severities=BLOCKER,CRITICAL&ps=100"

# Dashboard w przegladarce
# http://localhost:9000/dashboard?id=traffic-simulator
# http://localhost:9000/dashboard?id=traffic-simulator-frontend
```

## Uwagi

- Kontener nie ma persistent volume — po `docker rm` dane sa tracone (projekt, token, historia)
- Jesli chcesz zachowac dane miedzy restartami, uzyj `docker start/stop` zamiast `docker rm`
- Nowy kontener wymaga: zmiany hasla (min 12 znakow + special char), utworzenia projektu i tokena
- Analiza backend wymaga `mvn clean verify` przed `sonar:sonar` (potrzebuje skompilowanych klas + wynikow testow)
- Analiza frontend uzywa Docker image `sonarsource/sonar-scanner-cli` (nie wymaga instalacji sonar-scanner)
- `npx sonarqube-scanner` tez dziala (wersja 10.9.4) ale wymaga uruchomienia z katalogu root, nie frontend
