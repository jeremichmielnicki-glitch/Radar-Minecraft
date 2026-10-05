# Radar-Minecraft

Fizyczny radar do gry **Minecraft**, zbudowany z mikrokontrolera, serwomechanizmu i dwóch diod LED.

Urządzenie wykrywa najbliższego moba znajdującego się w pobliżu gracza i fizycznie wskazuje jego kierunek za pomocą serwomechanizmu oraz odpowiedniej diody LED.

## Jak działa projekt?

Projekt składa się z dwóch głównych części:

- **modu Minecraft** działającego na komputerze,
- **układu Arduino Pro Micro + SG90 + 2× LED**.

Mod Minecraft wykonuje całą logikę i obliczenia. Arduino pełni jedynie funkcję urządzenia wykonawczego — otrzymuje gotowy kąt oraz informację o diodzie, która ma zostać włączona.

---

## Urządzenie

Układ został zbudowany na bazie **Arduino Pro Micro**.

Arduino steruje:

- serwomechanizmem **SG90** za pomocą sygnału PWM,
- dwiema diodami LED.

SG90 posiada zakres obrotu około **180°**. Z tego względu zastosowano dwustronny orczyk, którego oba końce zostały wyposażone w diody LED.

Dzięki temu serwomechanizm może wskazywać kierunek w zakresie 0–180°, a odpowiednia dioda określa, po której stronie gracza znajduje się wykryty mob.

Arduino nie wykonuje obliczeń związanych z Minecraftem. Otrzymuje jedynie gotowe polecenie określające pozycję serwa i stan diod.

---

## Minecraft

Po stronie Minecrafta działa autorski mod odpowiedzialny za wykrywanie mobów i obliczanie ich położenia względem gracza.

Algorytm działania:

1. Mod wyszukuje wszystkie wrogie moby znajdujące się w promieniu **20 bloków** od gracza.
2. Odrzuca moby znajdujące się więcej niż **10 bloków wyżej względem osi Y**.
3. Spośród pozostałych mobów wybiera **najbliższego**.
4. Pobiera:
   - współrzędne gracza,
   - współrzędne moba,
   - kierunek patrzenia gracza.
5. Na podstawie tych danych oblicza położenie moba względem kierunku patrzenia gracza.
6. Określa, czy mob znajduje się przed, czy za graczem.
7. Na tej podstawie oblicza kąt w zakresie **0–180°**.
8. Wybiera odpowiednią diodę LED.
9. Wysyła wynik do Arduino przez port szeregowy.

Arduino otrzymuje więc już gotową informację:

> **kąt + odpowiednia dioda**

---

## Komunikacja

Komunikacja pomiędzy komputerem a Arduino odbywa się przez **UART**, wykorzystując wirtualny port szeregowy USB.

Konfiguracja:

| Parametr | Wartość |
|---|---|
| Port | `COM6` |
| Prędkość | `115200 baud` |
| Protokół | UART |
| Bufor wejściowy Arduino | 32 bajty |

### Format komendy

Mod wysyła polecenia w formacie:

```text
TARGET,XX,Y
```

gdzie:

- `XX` — kąt serwomechanizmu w zakresie `0–180°`,
- `Y` — numer diody (`1` lub `2`).

Przykład:

```text
TARGET,135,1
```

oznacza ustawienie serwa na **135°** oraz włączenie diody nr 1.

### Komenda `NONE`

Jeżeli w pobliżu nie znajduje się żaden odpowiedni mob, wysyłana jest komenda:

```text
NONE
```

Powoduje ona:

- ustawienie serwa w pozycji `0°`,
- wyłączenie obu diod.

### Odpowiedzi Arduino

Arduino odpowiada na każdą poprawną komendę, powtarzając otrzymane polecenie i dodając `OK`.

Przykładowo:

```text
OK,TARGET,135,1
```

Komunikacja zwrotna nie jest wykorzystywana przez mod Minecraft. Została dodana wyłącznie w celach **diagnostycznych i testowych**.

W przypadku nieprawidłowej komendy Arduino zwraca błąd:

```text
ERR,XYZ
```

gdzie `XYZ` określa rodzaj problemu, np.:

- nieprawidłowy format komendy,
- nieprawidłowy kąt,
- nieprawidłowy numer diody.

---

## Zasilanie

Podczas pierwszych testów Arduino ustawiające początkowy kąt serwa na 0° następnie się restartowało. Problem występował zarówno przy zasilaniu z portu USB komputera, jak i z ładowarki USB.

Problem został zidentyfikowany jako zbyt duży pobór prądu przez serwomechanizm. Zostało to rozwiązane poprzez zastosowanie osobnego zasilania **5 V / 1 A** dla serwomechanizmu, ze wspólną masą z mikrokontrolerem.

Dodatkowo zastosowano kondensatory filtrujące:

- **470 µF** — do ograniczenia spadków napięcia podczas pracy serwomechanizmu,
- **100 nF** — do filtracji zakłóceń wysokoczęstotliwościowych.

---

## Diagnostyka

Kolejne testy Arduino były wykonywanie niezależnie od Minecrafta.

Do portu szeregowego wysyłano ręcznie przykładowe komendy, sprawdzając:

- reakcję serwomechanizmu,
- działanie diod,
- poprawność odbierania danych,
- poprawność odpowiedzi Arduino.

Testy zakończyły się poprawnie.

Początkowe testy z modem Minecraft nie działały prawidłowo. Problem został zlokalizowany w matematyce odpowiedzialnej za określanie kierunku i kąta moba. Po poprawieniu obliczeń komunikacja pomiędzy modem a urządzeniem zaczęła działać prawidłowo.

Mod zapisuje również informacje diagnostyczne w **logu Minecraft**, co ułatwia śledzenie działania algorytmu i lokalizowanie problemów.

### Ważna uwaga dotycząca portu COM

Windows pozwala na jednoczesne otwarcie portu szeregowego tylko przez jedną aplikację.

Oznacza to, że nie można jednocześnie:

- uruchomić moda korzystającego z `COM6`,
- oraz otworzyć `COM6` w programie do ręcznej diagnostyki, np. monitorze szeregowym.

Do testów ręcznych należy najpierw zamknąć Minecraft lub wyłączyć komunikację moda z Arduino.

---

## Struktura repozytorium

```text
Radar-Minecraft/
│
├── MobIndicatorClient.java
├── mob-indicator.jar
│
└── Pictures/
    ├── ...
    ├── ...
    └── ...
```

### `MobIndicatorClient.java`

Surowy kod źródłowy moda Minecraft w języku **Java**.

### `mob-indicator.jar`

Gotowa, skompilowana wersja moda przeznaczona do instalacji w Minecraft.

### `Pictures/`

Materiały związane z projektem:

- zdjęcia urządzenia,
- schemat połączeń,
- prezentacja projektu.

---

## Technologie

**Hardware:**

- Arduino Pro Micro
- Servo SG90
- 2× LED
- rezystory 4,7k Ohm
- USB / UART

**Software:**

- Java
- Minecraft Mod
- Arduino / C++
- UART
- PWM

---

## Cel projektu

Projekt został wykonany jako połączenie programowania, elektroniki i integracji sprzętu z grą komputerową.

Najważniejszym założeniem było rozdzielenie odpowiedzialności pomiędzy komputer i mikrokontroler:

**Minecraft → obliczenia → UART → Arduino → serwo + LED**

Dzięki temu Arduino nie musi znać żadnych szczegółów dotyczących działania Minecrafta — wykonuje jedynie polecenia otrzymane z komputera.
