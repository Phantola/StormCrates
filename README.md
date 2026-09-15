# StormCrates 1.1.2 — Paper 26.2

Naprawiona wersja źródłowa pluginu StormCrates dla Paper 26.2.

## Wymagania
- Paper 26.2
- Java 25
- Gradle

## Budowanie
```bash
gradle build
```
Gotowy plik JAR pojawi się w `build/libs/StormCrates-1.1.2.jar`.

## Główna komenda
`/storm`

Typy skrzyń: `rzadka`, `epicka`, `mityczna`, `legendarna`, `custom`, `storm`.

Custom itemy: `ender_row`, `elytra_hook`, `soul_keeper`, `mini`, `slot_wiper`, `sphere`, `loot_core`, `dash_buckle`, `repair_kit`, `bedrock_slingshot`.

W tej wersji poprawiono m.in. obsługę cooldownów z config.yml, reset skali gracza, ochronę skrzyń i tymczasowych barier przed eksplozjami oraz logikę Soul Keepera.

## v5 — nowe skrzynie
- `budowniczego` — materiały budowlane + 5% Różdżka Budowniczego (5 bloków, 8 z Kostiumem Architekta).
- `chaosu` — losowy loot od śmieci po mocne nagrody, 2% Ziemniak Chaosu i 2% Jackpot (3 nagrody).

## v6: AirDrop + Achievements Storm + Storm Profile
- Automatyczny AirDrop co 45–90 minut (konfigurowalne), blokada 30 s, 4–7 nagród.
- `/storm profil [gracz]` — K/D, skrzynie, jackpoty, airdropy, osiągnięcia.
- `/storm osiagniecia` — GUI osiągnięć.
- `/storm airdrop start` — ręczny start dla administratora.
- Statystyki i osiągnięcia są zapisywane w `data.yml`.


## v1.1.2 — dodatkowe poprawki
- Otwarta skrzynia znika po użyciu klucza, więc nie można farmić jej w nieskończoność.
- Tasowarka Slotów nie zużywa cooldownu na pustym ekwipunku i ignoruje anulowane trafienia.
- Rozszerzony Ender Chest zapisuje dodatkowe sloty także przy wyjściu gracza.
- Komunikat Sfery korzysta z czasu ustawionego w config.yml.
- Promień AirDropu jest zabezpieczony przed nieprawidłową wartością 0/ujemną.
