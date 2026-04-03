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
- **Token (project analysis)**: `sqp_89935ee7d67a261ee77ecd2b5f0a3ee13d8d021a`
  - Typ: `PROJECT_ANALYSIS_TOKEN`
  - Projekt: `traffic-simulator`

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

```bash
# Z katalogu backend/ (lub root jesli pom.xml jest w root)
cd /home/sebastian/traffic-simulator

mvn clean verify sonar:sonar \
  -Dsonar.projectKey=traffic-simulator \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=sqp_89935ee7d67a261ee77ecd2b5f0a3ee13d8d021a
```

## Pobieranie wynikow (API)

```bash
# Wszystkie problemy
curl -s --user admin:Admin12345678! \
  "http://localhost:9000/api/issues/search?componentKeys=traffic-simulator&ps=100"

# Tylko BLOCKER i CRITICAL
curl -s --user admin:Admin12345678! \
  "http://localhost:9000/api/issues/search?componentKeys=traffic-simulator&severities=BLOCKER,CRITICAL&ps=100"

# Dashboard w przegladarce
# http://localhost:9000/dashboard?id=traffic-simulator
```

## Uwagi

- Kontener nie ma persistent volume — po `docker rm` dane sa tracone (projekt, token, historia)
- Jesli chcesz zachowac dane miedzy restartami, uzyj `docker start/stop` zamiast `docker rm`
- Nowy kontener wymaga: zmiany hasla (min 12 znakow + special char), utworzenia projektu i tokena
- Analiza wymaga `mvn clean verify` przed `sonar:sonar` (potrzebuje skompilowanych klas + wynikow testow)
