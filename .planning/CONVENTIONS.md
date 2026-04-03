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
