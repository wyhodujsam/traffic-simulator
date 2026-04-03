# Coding Conventions

Obowiązkowe reguły dla agenta kodującego. Czytaj przed każdą implementacją.

## Java Code Quality

**Cognitive complexity <= 15 per method.** Metoda nie powinna przekraczać cognitive complexity 15 (SonarQube java:S3776). Zasady:
- Metoda > 30 linii kodu = sygnał do rozbicia na mniejsze metody
- Zagnieżdżone pętle (for w for) = wydziel wewnętrzną pętlę do prywatnej metody
- Łańcuchy if/else z logiką biznesową = wydziel do metod z nazwami opisującymi intencję (np. `isSafeLaneChange()`, `checkBoxBlocking()`)
- Metoda-orkiestrator powinna delegować do pod-metod, nie zawierać logiki

**Każdy test musi mieć asercję.** (java:S2699) Nigdy nie pisz testów w stylu "jeśli nie rzuci wyjątku to OK". Zawsze dodaj `assertThat`/`assertEquals` weryfikujące faktyczny stan. `assertTrue(true)` to nie asercja.

**Nie duplikuj literałów stringowych.** (java:S1192) Jeśli string (np. `"Road "`) pojawia się >= 3 razy, wydziel do `private static final String CONSTANT`.

**Early return zamiast zagnieżdżania.** Zamiast `if (x != null) { if (y > 0) { ... } }` pisz guard clause: `if (x == null) return;` + `if (y <= 0) return;` + flat code.

**Prywatne metody grupuj przy metodzie-rodzicu.** Wydzielone helpery trzymaj bezpośrednio pod metodą, która ich używa — nie rozrzucaj po klasie.

**Konstruktor injection zamiast @Autowired na polach.** (java:S6813) Opcjonalne zależności: `@Nullable` parametr w konstruktorze. Circular dependencies: `@Lazy` na parametrze konstruktora + package-private setter w jednej ze stron.

**Każda zmienna na osobnej linii.** (java:S1659) Zamiast `double x = ..., y = ...;` pisz dwie deklaracje.

**Merge zagnieżdżone if-y.** (java:S1066) Zamiast `if (a) { if (b) { ... } }` pisz `if (a && b) { ... }`.

**Pętle z max 1 break/continue.** (java:S135) Jeśli pętla wymaga >1 break/continue, wydziel ciało pętli do prywatnej metody z early return.

**Testy: `.isZero()` zamiast `.isEqualTo(0)`.** (java:S5838) Analogicznie `.isEmpty()`, `.isNotNull()` itp.

**Testy: łącz asercje na tym samym obiekcie w chain.** (java:S5853) `assertThat(x).isNotNull().isEqualTo(y)` zamiast dwóch osobnych `assertThat(x)`.

**Metody max 7 parametrów.** (java:S107) Powyżej 7 — wprowadź record grupujący powiązane parametry.

**Nie ignoruj wartości zwracanych.** (java:S899) Jeśli celowo ignorujesz — dodaj `@SuppressWarnings("java:S899")` z komentarzem dlaczego.

**Nie deklaruj throws Exception, gdy metoda nie rzuca checked exception.** (java:S1130) Dotyczy szczególnie testów.

## TypeScript/React Code Quality

**Cognitive complexity <= 15 per function.** (typescript:S3776) Te same zasady co Java — wydzielaj helpery z dużych funkcji renderujących (np. `drawClosedLaneHatching`, `drawRoadBoundaries`).

**`Math.hypot(x, y)` zamiast `Math.sqrt(x*x + y*y)`.** (typescript:S7769) Czytelniejsze i bezpieczniejsze numerycznie.

**`Number.parseFloat()` / `Number.parseInt()` zamiast globalnych.** (typescript:S7773) Preferuj metody na obiekcie Number.

**`Math.trunc()` zamiast `| 0`.** (typescript:S7767) Czytelniejsze.

**`codePointAt()` zamiast `charCodeAt()`.** (typescript:S7758) Poprawna obsługa Unicode.

**Nie używaj zbędnych `.0` w literałach.** (typescript:S7748) `2` zamiast `2.0`, `14` zamiast `14.0`.

**Bez zagnieżdżonych ternary.** (typescript:S3358) Wydziel do osobnej funkcji helper.

**Optional chaining zamiast ręcznego null-checka.** (typescript:S6582) `prev?.roadId` zamiast `prev !== undefined && prev.roadId`.

**Unikaj negated conditions w if/else.** (typescript:S7735) Pisz pozytywny warunek w if, negatywny w else.

**Props interfejsy oznaczaj jako `readonly`.** (typescript:S6759) `interface Props { readonly label: string; }`.

**Form label musi być powiązany z kontrolką.** (typescript:S6853) Użyj `htmlFor`/`id` lub zagnieźdź input wewnątrz label.

**Usuwaj nieużywane zmienne.** (typescript:S1854) Nie przypisuj wartości do zmiennych, których nie używasz.
