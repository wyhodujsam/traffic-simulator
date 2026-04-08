Feature: Combined scenario with roundabout, signal, merge and loop
  As a traffic simulator user
  I want a complex scenario combining all intersection types
  So that I can observe emergent traffic patterns in a realistic network

  Background:
    Given mapa "combined-loop" jest załadowana

  @MAP-01
  Scenario: Combined loop map loads correctly
    Then mapa jest załadowana poprawnie
    And droga sieciowa zawiera co najmniej 15 drogę

  @MAP-01
  Scenario: Vehicles spawn and traverse the network
    When uruchamiam symulację
    And ustawiam tempo spawnowania na 3.0 pojazdów/s
    And wykonuję 200 ticków z krokiem 0.05s
    Then na drodze jest co najmniej 1 pojazd

  @MAP-01
  Scenario: Traffic lights cycle at signalized intersection
    When uruchamiam symulację
    And wykonuję 1000 ticków z krokiem 0.05s
    Then światła na skrzyżowaniu zmieniły fazę co najmniej 2 razy

  @MAP-01
  Scenario: Vehicles stop at red light in combined scenario
    When uruchamiam symulację
    And ustawiam tempo spawnowania na 3.0 pojazdów/s
    And wykonuję 600 ticków z krokiem 0.05s
    Then istnieją pojazdy z prędkością 0.0
