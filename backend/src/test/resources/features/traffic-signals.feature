Feature: Traffic signals and intersections
  As a traffic simulator user
  I want to observe traffic lights at intersections
  So that I can study the impact of signals on traffic flow

  @IXTN-01
  Scenario: Traffic lights cycle through phases
    Given mapa "four-way-signal" jest załadowana
    When uruchamiam symulację
    And wykonuję 1000 ticków z krokiem 0.05s
    Then światła na skrzyżowaniu zmieniły fazę co najmniej 2 razy

  @IXTN-02
  Scenario: Vehicles stop at red light
    Given mapa "four-way-signal" jest załadowana
    When uruchamiam symulację
    And ustawiam tempo spawnowania na 3.0 pojazdów/s
    And wykonuję 800 ticków z krokiem 0.05s
    Then istnieją pojazdy z prędkością 0.0

  @IXTN-03
  Scenario: Box-blocking prevention at intersection
    Given mapa "four-way-signal" jest załadowana
    When uruchamiam symulację
    And ustawiam tempo spawnowania na 5.0 pojazdów/s
    And wykonuję 400 ticków z krokiem 0.05s
    Then żaden pojazd nie blokuje skrzyżowania
