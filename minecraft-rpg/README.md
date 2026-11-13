# RPGCore – RPG plugin pro Minecraft (Paper 1.21.11)

Kompletní RPG systém v jednom pluginu, včetně vlastního resource packu.
Nepotřebuje žádné další pluginy.

## Co umí

- **203 vlastních předmětů** – meče, katany, sekery, palcáty, kladiva, kosy, kopí, dýky, hole, hůlky, luky,
  13 setů brnění (52 kusů), doplňky, lektvary, materiály, bedny. Šest stupňů vzácnosti.
- **4 třídy** (Válečník, Mág, Lučištník, Zloděj), každá se 4 dovednostmi. Výběr třídy má animované menu.
- **Úrovně 1–100**, body atributů (Síla, Výdrž, Inteligence, Obratnost), mana, kritické zásahy, krádež života.
- **34 vlastních mobů** v kostýmech z resource packu. Čím dál od spawnu, tím silnější.
- **8 bossů** s boss barem, fázemi, ohlášenými útoky, poskoky a vlastní kořistí pro každého hráče.
- **NPC** se skiny z resource packu: zadavatelé úkolů, obchodníci, kovář, trenér tříd, léčitelka.
- **16 úkolů** tvořících příběh přes všechny bosse, plus opakovatelné.
- **Kovář** – vylepšování +1 až +10 a 79 receptů na výrobu.
- **Bedny** s animací losování (6 druhů).
- **Postupné odměny** – denní série, odměny za úrovně a za odehraný čas (`/rewards`).
- **Vlastní vzhled menu** a ikony v HUD z resource packu, ukazatele poškození nad moby.

## Instalace

1. Server musí být **Paper 1.21.11** (Java 21).
2. Zkopíruj `dist/RPGCore.jar` do složky `plugins/` a restartuj server.
3. Otevři na routeru/hostingu **port 8163** (TCP). Přes něj plugin posílá hráčům resource pack.
   Pokud ti hosting další port nedá, nahraj `dist/RPGCore-resourcepack.zip` kamkoli na web
   a v `plugins/RPGCore/config.yml` nastav `resource-pack.mode: url` a `resource-pack.url`.

## První kroky na serveru (jako OP)

```
/rpg npc spawn elder        postaví NPC tam, kde stojíš
/rpg npc spawn trainer      (dál: guard, hunter, merchant, alchemist, blacksmith, healer)
/rpg items                  menu se všemi předměty
/rpg mob goblin             vytvoří moba
/rpg boss spawn goblin_king vytvoří bosse
/rpg boss arena goblin_king 30    boss se tu bude obnovovat 30 minut po porážce
/rpg spawner add bandit 6 4 15    spawner: okruh 6, max 4 moby, každých 15 s
```

Další admin příkazy: `/rpg give <hráč> <předmět> [počet]`, `/rpg xp|level|setcoins <hráč> <číslo>`,
`/rpg boss list|kill|delarena`, `/rpg npc list|remove`, `/rpg spawner list|remove`, `/rpg pack`, `/rpg reload`.

## Příkazy hráčů

| Příkaz | Co dělá |
|---|---|
| `/rpg` | menu postavy |
| `/class` | výběr třídy |
| `/skills` | dovednosti a ovládání |
| `/stats` | rozdělení bodů atributů |
| `/quests` | deník úkolů |
| `/rewards` | denní a postupové odměny |
| `/rpg acc` | doplňky |

## Ovládání v boji

Se zbraní v ruce:

| Klávesy | Akce |
|---|---|
| pravý klik | schopnost zbraně (hole tím střílí) |
| Shift + pravý klik | 1. dovednost třídy |
| Shift + levý klik | 2. dovednost |
| F | 3. dovednost |
| Shift + F | 4. dovednost |

## Úpravy

Všechno je v `plugins/RPGCore/*.yml` a jde měnit bez programování:

| Soubor | Obsah |
|---|---|
| `config.yml` | resource pack, úrovně, boj, HUD |
| `items.yml` | předměty a sety brnění |
| `mobs.yml` | moby |
| `bosses.yml` | bossové, jejich fáze a schopnosti |
| `npcs.yml` | NPC |
| `quests.yml` | úkoly |
| `shops.yml` | obchody a recepty kováře |
| `crates.yml` | obsah beden |
| `rewards.yml` | postupné odměny |

Po změně stačí `/rpg reload`.

### Nové textury

Ikony se kreslí ze šablon v `tools/generate_pack.py` a přebarvují podle `colors` v `items.yml`.

```
pip install pillow pyyaml
python3 tools/generate_pack.py --sheet nahled.png   # vygeneruje pack + náhled všech textur
python3 tools/validate.py                           # zkontroluje odkazy mezi soubory
cd plugin && gradle build                           # sestaví plugin (jar je v plugin/build/libs)
```

Až budeš chtít hezčí grafiku, stačí přepsat PNG ve složce `resourcepack/assets/rpg/textures/`
(názvy souborů musí zůstat) a pack znovu zabalit – nebo vypnout `resource-pack.use-bundled`
a dát vlastní zip do `plugins/RPGCore/resourcepack.zip`.

## Co plugin zatím nemá

- 3D modely a animace bossů (bossové jsou moby v kostýmech; na 3D modely je potřeba Blockbench + ModelEngine/BetterModel)
- vlastní zvuky
- party, guildy, dungeony, PvP arény
