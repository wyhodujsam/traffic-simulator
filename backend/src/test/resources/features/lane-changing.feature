Feature: Lane changing and road narrowing
  As a traffic simulator user
  I want to observe lane changes and behavior during narrowing
  So that I can study the impact of maneuvers on traffic flow

  Background:
    Given mapa "straight-road" jest załadowana

  @ROAD-02
  Scenario: Vehicles change lanes using MOBIL model
    When uruchamiam symulację
    And ustawiam tempo spawnowania na 5.0 pojazdów/s
    And wykonuję 400 ticków z krokiem 0.05s
    Then co najmniej 1 pojazd zmienił pas

  @ROAD-04
  Scenario: Road narrowing closes a lane
    When zamykam pas 2 na drodze "r1"
    Then pas "r1-lane2" jest nieaktywny
