#!/usr/bin/env python3
"""
Generátor resource packu pro RPGCore.

Čte plugin/src/main/resources/items.yml a vytvoří:
  - ikony všech předmětů (pixel art 16x16 ze šablon, přebarvený podle `colors`)
  - modely a definice předmětů (formát 1.21.4+, komponenta item_model)
  - textury brnění na těle (sety hráčů + kostýmy mobů a bossů)
  - skiny NPC (64x64)
  - ikony do HUD (font)
  - zip do plugin/src/main/resources/resourcepack.zip a dist/

Použití:  python3 tools/generate_pack.py [--sheet nahled.png]
Vyžaduje: pip install pillow pyyaml
"""
import argparse
import colorsys
import json
import random
import shutil
import zipfile
from pathlib import Path

import yaml
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ITEMS_YML = ROOT / "plugin/src/main/resources/items.yml"
OUT = ROOT / "resourcepack"
ZIP_PLUGIN = ROOT / "plugin/src/main/resources/resourcepack.zip"
ZIP_DIST = ROOT / "dist/RPGCore-resourcepack.zip"
NS = "rpg"

# pack_format pro 1.21.11; rozsah drží pack použitelný i na novějších verzích
PACK_FORMAT = 75
PACK_FORMAT_MIN = 46   # 1.21.4 - první verze s definicemi předmětů (items/)
PACK_FORMAT_MAX = 999


# ----------------------------------------------------------------------------
#  Barvy
# ----------------------------------------------------------------------------
def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def shade(rgb, light, hue_shift=0.0, sat=1.0):
    """Zesvětlí/ztmaví barvu; světla táhne do žluta, stíny do modra (klasika pixel artu)."""
    r, g, b = [c / 255 for c in rgb]
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    h = (h + hue_shift) % 1.0
    s = max(0.0, min(1.0, s * sat))
    v = max(0.0, min(1.0, v * light))
    if light > 1.0:
        # u světlých odstínů ubereme sytost, aby nepřepalovaly
        s *= max(0.35, 1.0 - (light - 1.0) * 0.9)
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return (round(r * 255), round(g * 255), round(b * 255))


def ramp5(hexcol):
    """5 odstínů: nejsvětlejší -> nejtmavší."""
    c = hex_rgb(hexcol)
    return [
        shade(c, 1.45, -0.02),
        shade(c, 1.2, -0.01),
        c,
        shade(c, 0.74, 0.015, 1.08),
        shade(c, 0.52, 0.03, 1.12),
    ]


def ramp3(hexcol):
    c = hex_rgb(hexcol)
    return [shade(c, 1.3, -0.015), c, shade(c, 0.66, 0.02, 1.1)]


def outline_of(hexcol):
    return shade(hex_rgb(hexcol), 0.26, 0.04, 1.1)


def palette(colors):
    main, sec, acc = colors
    m = ramp5(main)
    s = ramp3(sec)
    a = ramp3(acc)
    return {
        "#": outline_of(main),
        "A": m[0], "B": m[1], "C": m[2], "D": m[3], "E": m[4],
        "a": s[0], "b": s[1], "c": s[2],
        "x": a[0], "y": a[1], "z": a[2],
        "W": (255, 255, 255),
    }


# ----------------------------------------------------------------------------
#  Šablony ikon 16x16
#  .  průhledná      #  obrys
#  A B C D E  hlavní materiál (světlá -> tmavá)
#  a b c      rukojeť / lem
#  x y z      drahokam / záře
#  W          bílý odlesk
# ----------------------------------------------------------------------------
T = {}

T["sword"] = [
    ".............###",
    "............#AB#",
    "...........#ABC#",
    "..........#ABC#.",
    ".........#ABC#..",
    "........#ABC#...",
    ".##....#ABC#....",
    ".#x#..#ABC#.....",
    "..#y##BCD#......",
    "...#yyCD#.......",
    "...#zyy#........",
    "..#ab#yy#.......",
    ".#ab#.#zy#......",
    "#xb#...#z#......",
    "#yx#....##......",
    "###.............",
]

T["greatsword"] = [
    "............####",
    "...........#AAB#",
    "..........#ABBC#",
    ".........#ABBC#.",
    "........#ABBC#..",
    ".......#ABBC#...",
    "......#ABBC#....",
    "##...#ABBC#.....",
    "#x#.#ABBC#......",
    "#yx#BBCD#.......",
    ".#yxxCD#........",
    "..#zxx#.........",
    ".#ab#zx#........",
    "#ab#.#zy#.......",
    "#b#...###.......",
    "##..............",
]

T["dagger"] = [
    "................",
    "................",
    "................",
    "..........###...",
    ".........#AB#...",
    "........#ABC#...",
    ".......#ABC#....",
    "..##..#ABC#.....",
    "..#y##BCD#......",
    "...#yyCD#.......",
    "....#yy#........",
    "...#ab#y#.......",
    "..#ab#.##.......",
    ".#xb#...........",
    ".###............",
    "................",
]

T["axe"] = [
    "................",
    "......####......",
    ".....#AABB##....",
    "....#ABBBCCa#...",
    "....#ABBCCab#...",
    "....#BBCCab#C#..",
    ".....#CCab#CD#..",
    "......#ab#CCD#..",
    ".....#ab#.#D#...",
    "....#ab#...#....",
    "...#ab#.........",
    "..#ab#..........",
    ".#ab#...........",
    "#ab#............",
    "#b#.............",
    "##..............",
]

T["hammer"] = [
    "......##........",
    ".....#AB#.......",
    "....#ABBC#......",
    "....#BBBCC#.....",
    ".....#BxBCC#....",
    "......#BCCCD#...",
    ".......#CCDDD#..",
    "......#a#CDDD#..",
    ".....#ab##DD#...",
    "....#ab#..##....",
    "...#ab#.........",
    "..#ab#..........",
    ".#ab#...........",
    "#xb#............",
    "#y#.............",
    "##..............",
]

T["scythe"] = [
    "................",
    "...#######......",
    ".##AAABBBCC#a#..",
    "#AABBB###CCab#..",
    "#BB###...#ab#...",
    "###.....#ab#....",
    ".......#ab#.....",
    "......#ab#......",
    ".....#ab#.......",
    "....#ab#........",
    "...#ab#.........",
    "..#ab#..........",
    ".#ab#...........",
    "#xb#............",
    "#y#.............",
    "##..............",
]

T["spear"] = [
    "............####",
    "...........#AAB#",
    "..........#ABBC#",
    "..........#BBC#.",
    ".........#xCC#..",
    "........#ab###..",
    ".......#ab#.....",
    "......#ab#......",
    ".....#ab#.......",
    "....#ab#........",
    "...#ab#.........",
    "..#ab#..........",
    ".#ab#...........",
    "#ab#............",
    "#b#.............",
    "##..............",
]

T["staff"] = [
    "..........####..",
    ".........#xxyy#.",
    "........##xWyz#.",
    ".......#a#yyzz#.",
    ".......#ba####..",
    "......#BCba#....",
    ".....#BC###.....",
    "....#BC#........",
    "...#BC#.........",
    "..#BC#..........",
    ".#BC#...........",
    "#BC#............",
    "#C#.............",
    "##..............",
    "................",
    "................",
]

T["wand"] = [
    "................",
    "............x...",
    "...........xWx..",
    "..........##x#..",
    ".........#xy#...",
    "........#ay#....",
    ".......#Ba#.....",
    "......#BC#......",
    ".....#BC#.......",
    "....#BC#........",
    "...#BC#.........",
    "..#aC#..........",
    "..#b#...........",
    "..##............",
    "................",
    "................",
]

# luk bez tětivy - tětivu a šíp dokresluje kód (kvůli snímkům natahování)
T["bow"] = [
    "................",
    ".......#######..",
    ".....##ABBBBCx#.",
    "....#AAB#####...",
    "...#AB##........",
    "..#AB#..........",
    "..#AB#..........",
    ".#AB#...........",
    ".#BB#...........",
    ".#BB#...........",
    ".#BC#...........",
    ".#BC#...........",
    ".#Cx#...........",
    "..##............",
    "................",
    "................",
]

T["helmet"] = [
    "................",
    "................",
    "....########....",
    "...#AABBBBCC#...",
    "..#AABBxyBBCC#..",
    "..#ABBBBBBBCD#..",
    "..#abbbbbbbbc#..",
    "..#BBB####BCD#..",
    "..#BB#....#CD#..",
    "..#BC#....#CD#..",
    "..#BC#....#DD#..",
    "..#CD#....#DE#..",
    "..####....####..",
    "................",
    "................",
    "................",
]

T["chestplate"] = [
    "................",
    "..###......###..",
    ".#AAB#....#BBC#.",
    ".#ABBB####BBCD#.",
    ".#ABBBBBBBBCCD#.",
    ".#BB#BBxyBB#CD#.",
    ".###BBBxyBBC###.",
    "...#BBBBBBCC#...",
    "...#BBBBBBCD#...",
    "...#BBBBBCCD#...",
    "...#BBBBCCCD#...",
    "...#abbbbbbc#...",
    "...#CCCCCDDD#...",
    "...##########...",
    "................",
    "................",
]

T["leggings"] = [
    "................",
    "...##########...",
    "...#abbxybbc#...",
    "...#ABBBBCCD#...",
    "...#ABBBBCCD#...",
    "...#ABB##CCD#...",
    "...#AB#..#CD#...",
    "...#BB#..#CD#...",
    "...#BB#..#CD#...",
    "...#BC#..#CD#...",
    "...#BC#..#DD#...",
    "...#BC#..#DD#...",
    "...#CC#..#DE#...",
    "...####..####...",
    "................",
    "................",
]

T["boots"] = [
    "................",
    "................",
    "................",
    "................",
    "...####..####...",
    "...#AB#..#BC#...",
    "...#AB#..#BC#...",
    "...#BB#..#CC#...",
    "...#ab#..#bc#...",
    "..#ABB#..#BCC#..",
    ".#ABBB#..#BCCD#.",
    ".#BBCC#..#CCDD#.",
    ".######..######.",
    "................",
    "................",
    "................",
]

T["hood"] = [
    "................",
    "......####......",
    "....##AABB##....",
    "...#AABBBBCC#...",
    "..#ABBBBBBBCC#..",
    "..#ABB####BCD#..",
    ".#ABB#....#BCD#.",
    ".#ABB#....#CCD#.",
    ".#BBC#....#CDD#.",
    ".#BCC#....#CDD#.",
    ".#abbb#..#bbbc#.",
    "..#####..#####..",
    "................",
    "................",
    "................",
    "................",
]

T["robe"] = [
    "................",
    "..###......###..",
    ".#AAB#....#BBC#.",
    ".#ABBB####BBCD#.",
    ".#ABBBBxyBBBCD#.",
    ".#BB#BBabBB#CD#.",
    ".#BB#BBabBC#CD#.",
    ".###BBBabBCC###.",
    "...#BBBabBCD#...",
    "...#BBBabCCD#...",
    "...#BBBabCCD#...",
    "..#BBBBabCCDD#..",
    "..#BBBCabCDDD#..",
    "..############..",
    "................",
    "................",
]

T["ring"] = [
    "................",
    "......####......",
    ".....#xWyy#.....",
    ".....#xyyz#.....",
    "....##yzzz##....",
    "...#AB####BC#...",
    "..#AB#....#BC#..",
    "..#AB#....#CD#..",
    "..#BB#....#CD#..",
    "..#BC#....#CD#..",
    "...#BC####CD#...",
    "....#CCCCDD#....",
    ".....######.....",
    "................",
    "................",
    "................",
]

T["amulet"] = [
    "..##........##..",
    ".#AB#......#BC#.",
    "..#AB#....#BC#..",
    "...#AB#..#BC#...",
    "....#AB##BC#....",
    ".....#ABBC#.....",
    ".....######.....",
    "....#axWxyb#....",
    "....#axxyyb#....",
    "....#bxyyzb#....",
    "....#byyzzc#....",
    ".....#byzc#.....",
    "......#bc#......",
    ".......##.......",
    "................",
    "................",
]

T["charm"] = [
    "......####......",
    ".....#a##a#.....",
    ".....#a..a#.....",
    "......#aa#......",
    ".....##AB##.....",
    "....#AABBBC#....",
    "...#AABxxBCC#...",
    "...#ABxWyyCD#...",
    "...#BBxyyzCD#...",
    "...#BCCyzCDD#...",
    "....#CCCDDD#....",
    ".....#CDDD#.....",
    "......#DD#......",
    ".......##.......",
    "................",
    "................",
]

T["belt"] = [
    "................",
    "................",
    "................",
    "................",
    "......####......",
    ".#####xxyy#####.",
    "#ABBB#xWyz#BBCD#",
    "#BBBC#x##z#BCCD#",
    "#BBCC#xyzz#CCDD#",
    ".#####yzzz#####.",
    "......####......",
    "................",
    "................",
    "................",
    "................",
    "................",
]

T["feather"] = [
    "............##..",
    "..........##AB#.",
    ".........#AABx#.",
    "........#AABxC#.",
    ".......#AABxCC#.",
    "......#AABxCC#..",
    ".....#AABxCCD#..",
    ".....#ABxCCD#...",
    "....#ABxCCD#....",
    "....#BxCCD#.....",
    "...#axCDD#......",
    "...#a####.......",
    "..#a#...........",
    ".#a#............",
    ".##.............",
    "................",
]

T["potion"] = [
    "................",
    "......####......",
    "......#xy#......",
    ".....######.....",
    "......#ab#......",
    ".....##ab##.....",
    "....#aaabbb#....",
    "...#aABBBBCb#...",
    "...#AWABBBCC#...",
    "...#AABBBBCD#...",
    "...#ABBBBCCD#...",
    "...#BBBBCCDD#...",
    "....#BCCCDD#....",
    ".....######.....",
    "................",
    "................",
]

T["potion_large"] = [
    ".....######.....",
    "......#xy#......",
    "......#xz#......",
    ".....######.....",
    "......#ab#......",
    "....###ab###....",
    "...#aaaabbbb#...",
    "..#aABBBBBBCb#..",
    "..#AWABBBBBCC#..",
    "..#AABBBBBBCD#..",
    "..#xxxxxxxxyz#..",
    "..#ABBBBBCCDD#..",
    "..#BBBBCCCDDD#..",
    "...#BCCCCDDD#...",
    "....########....",
    "................",
]

T["bread"] = [
    "................",
    "................",
    "................",
    "................",
    "....########....",
    "..##AABBBBBB##..",
    ".#AABxBBxBBxBC#.",
    "#AABBxBBxBBxBCD#",
    "#ABBBBBBBBBBCCD#",
    "#BBBBBBBBBCCCDD#",
    ".#CCCCCCCCDDDD#.",
    "..############..",
    "................",
    "................",
    "................",
    "................",
]

T["tome"] = [
    "................",
    "...##########...",
    "..#ABBBBBBBBC#..",
    "..#AaBBBBBBaC#..",
    "..#ABBBaaBBBC#..",
    "..#ABBaWWaBBC#..",
    "..#ABBaWWaBBC#..",
    "..#ABBBaaBBBC#..",
    "..#ABBBBBBBBC#..",
    "..#AaBBBBBBaC#..",
    "..#BCCCCCCCCD#..",
    "..#xxxxxxxxxy#..",
    "..#DDDDDDDDDE#..",
    "...##########...",
    "................",
    "................",
]

T["scroll"] = [
    "................",
    "................",
    "..###########...",
    ".#aAAAAAAAAAb#..",
    ".#abbbbbbbbbc#..",
    "..#ABBBBBBBC#...",
    "..#ABDDDDDBC#...",
    "..#ABBBBBBBC#...",
    "..#ABDDDDBBC#...",
    "..#ABBBBxyBC#...",
    "..#ABBBBBBBC#...",
    ".#aAAAAAAAAAb#..",
    ".#abbbbbbbbbc#..",
    "..###########...",
    "................",
    "................",
]

T["stone"] = [
    "................",
    "................",
    "......####......",
    "....##AAAB##....",
    "...#AAWABBBC#...",
    "..#AAAABBBBCC#..",
    "..#AABBBBBCCC#..",
    "..#ABBBBBCCCD#..",
    "..#BBBBBCCCDD#..",
    "...#BBCCCCDD#...",
    "....#CCCDDD#....",
    ".....#CDDD#.....",
    "......#DD#......",
    ".......##.......",
    "................",
    "................",
]

T["ear"] = [
    "................",
    "................",
    "..........###...",
    "........##AB#...",
    "......##AABC#...",
    "....##AABxBC#...",
    "...#AABBxxBC#...",
    "...#ABBxxyBCD#..",
    "....#BBxyyCCD#..",
    ".....#BByCCD#...",
    "......#CCCDD#...",
    ".......#CDD#....",
    "........###.....",
    "................",
    "................",
    "................",
]

T["bone"] = [
    "................",
    "..........##.##.",
    ".........#AB#AB#",
    ".........#ABBBC#",
    "..........#BBC#.",
    ".........#ABC#..",
    "........#ABC#...",
    ".......#ABC#....",
    "......#ABC#.....",
    ".....#ABC#......",
    "....#ABC#.......",
    "..##ABC#........",
    ".#ABBBC#........",
    ".#BC#BC#........",
    "..##.##.........",
    "................",
]

T["pelt"] = [
    "................",
    "..##........##..",
    ".#AB##....##BC#.",
    ".#ABBB####BBCD#.",
    "..#ABBBBBBBCD#..",
    "..#ABBxBBxBCD#..",
    "...#BBBBBBCD#...",
    "...#BBBxBBCD#...",
    "...#BBBBBBCD#...",
    "..#ABBxBBBCCD#..",
    "..#BBBBBBxCCD#..",
    ".#ABBB####CCDD#.",
    ".#BC##....##DD#.",
    "..##........##..",
    "................",
    "................",
]

T["silk"] = [
    "................",
    "................",
    "................",
    ".....######.....",
    "...##AAABBB##...",
    "..#AAcBBBBcCC#..",
    "..#ABBcBBcBCC#..",
    "..#ABBBccBBCD#..",
    "..#ABBcBBcBCD#..",
    "..#BBcBBBBcCD#..",
    "...##CCCCDD##...",
    ".....######.....",
    "................",
    "................",
    "................",
    "................",
]

T["badge"] = [
    "................",
    "..############..",
    "..#AABBBBBBCC#..",
    "..#ABBBxxBBCD#..",
    "..#ABBxWyyBCD#..",
    "..#ABBxyyzBCD#..",
    "..#ABBByzBBCD#..",
    "..#BBBBBBBCCD#..",
    "...#BBBBBCCD#...",
    "...#BBBBCCDD#...",
    "....#BCCCDD#....",
    ".....#CCDD#.....",
    "......#DD#......",
    ".......##.......",
    "................",
    "................",
]

T["cloth"] = [
    "................",
    "................",
    "................",
    "..###########...",
    ".#AABBBBBBBCC#..",
    ".#ABBxBBBxBBCD#.",
    ".#BBBBBBBBBCCD#.",
    "..############..",
    ".#ABBBBBBBBCCD#.",
    ".#ABxBBBxBBBCD#.",
    ".#BBBBBBBBCCDD#.",
    ".#CCCCCCCCDDDD#.",
    "..############..",
    "................",
    "................",
    "................",
]

T["ingot"] = [
    "................",
    "................",
    "................",
    "................",
    "....#########...",
    "...#AAAAAAAB##..",
    "..#AWABBBBBC#D#.",
    ".#ABBBBBBBC#CD#.",
    ".##########CCD#.",
    ".#BBBBBBBC#CD#..",
    ".#BCCCCCCD#D#...",
    ".###########....",
    "................",
    "................",
    "................",
    "................",
]

T["dust"] = [
    "................",
    "................",
    "....x.....x.....",
    "...xWx...xWx....",
    "....x..x..x.....",
    "......xWx.......",
    ".......x........",
    "......####......",
    "....##AABB##....",
    "...#AABxBBCC#...",
    "..#AABBBBxBCC#..",
    ".#ABxBBBBBBCCD#.",
    ".#BBBBBxBBCCDD#.",
    "..############..",
    "................",
    "................",
]

T["crystal"] = [
    ".......##.......",
    "......#AB#......",
    "......#AB#......",
    ".....#AABC#.....",
    ".....#AWBC#.##..",
    "..##.#AABC##BC#.",
    ".#AB##ABBC#BCD#.",
    ".#ABB#ABCC#BCD#.",
    ".#ABC#ABCD#CCD#.",
    ".#BBC#BBCD#CDD#.",
    ".#BCC#BCCD#CDD#.",
    "..#CD#BCDD#DD#..",
    "...###CDDD###...",
    ".....######.....",
    "................",
    "................",
]

T["orb"] = [
    "................",
    "................",
    ".....######.....",
    "...##AAAABB##...",
    "..#AAWWAABBBC#..",
    "..#AWWABBBBCC#..",
    ".#AAWABxxBBBCC#.",
    ".#AABBxWWxBBCD#.",
    ".#ABBBxWWxBCCD#.",
    ".#BBBBBxxBCCDD#.",
    "..#BBBBBCCCDD#..",
    "..#BCCCCCDDDD#..",
    "...##CDDDDD##...",
    ".....######.....",
    "................",
    "................",
]

T["wisp"] = [
    "........#.......",
    ".......#A#......",
    ".......#AB#.....",
    "......#AAB#.....",
    ".....#AABB#.#...",
    ".....#ABBC##B#..",
    "....#AABxBCBC#..",
    "...#AABxxBBCC#..",
    "...#ABxxWxBCD#..",
    "...#ABxWWxBCD#..",
    "...#BBxxxxCCD#..",
    "....#BBxxCCD#...",
    ".....#CCCDD#....",
    "......#####.....",
    "................",
    "................",
]

T["heart"] = [
    "................",
    "................",
    "...###...###....",
    "..#AAB#.#BBC#...",
    ".#AWABB#BBBCC#..",
    ".#AABBBBBBBCD#..",
    ".#ABBBxBBBCCD#..",
    ".#BBBxxxBBCCD#..",
    "..#BBBxBBCCD#...",
    "...#BBBBCCD#....",
    "....#BBCCD#.....",
    ".....#CCD#......",
    "......#D#.......",
    ".......#........",
    "................",
    "................",
]

T["rune"] = [
    "................",
    "....########....",
    "...#AABBBBCC#...",
    "..#AABBxBBBCC#..",
    "..#ABBxxxBBCD#..",
    "..#ABxBxBxBCD#..",
    "..#ABBBxBBBCD#..",
    "..#ABBBxBBBCD#..",
    "..#ABBxxxBBCD#..",
    "..#BBxBxBxBCD#..",
    "..#BBBBxBBCDD#..",
    "..#BCCCCCCDDD#..",
    "...#CCDDDDDD#...",
    "....########....",
    "................",
    "................",
]

T["scale"] = [
    "................",
    "......####......",
    "....##AABB##....",
    "...#AAABBBBC#...",
    "..#AAWBBBBBCC#..",
    "..#AABBBxBBCC#..",
    "..#ABBBBxBBCD#..",
    "..#ABBBBxBBCD#..",
    "..#BBBBBxBCCD#..",
    "...#BBBBxBCD#...",
    "...#BBCCxCDD#...",
    "....#CCCxDD#....",
    ".....#CDDD#.....",
    "......####......",
    "................",
    "................",
]

T["soul"] = [
    "................",
    ".....######.....",
    "...##AAAABB##...",
    "..#AAWAABBBBC#..",
    "..#AWAABBBBCC#..",
    ".#AAA##BB##BCC#.",
    ".#AAB#xBB#xBCD#.",
    ".#ABB##BB##CCD#.",
    ".#ABBBBBBBBCCD#.",
    "..#BBBB##BCCD#..",
    "..#BBCCBBCCDD#..",
    "...#BC##CC#DD#..",
    "...###..##.##...",
    "................",
    "................",
    "................",
]

T["horn"] = [
    "................",
    "..........####..",
    ".........#AABC#.",
    "........#AABBCD#",
    ".......#AABBCD#.",
    "......#aABBCD#..",
    ".....#abBBCD#...",
    "....#AabBCD#....",
    "...#AABbCD#.....",
    "..#AABCD##......",
    ".#ABBCD#........",
    ".#xBCD#.........",
    ".#xy##..........",
    "..##............",
    "................",
    "................",
]

T["skull"] = [
    "................",
    "....########....",
    "...#AAABBBBC#...",
    "..#AAWBBBBBCC#..",
    "..#AABBBBBBCD#..",
    "..#A###BB###D#..",
    "..#A#xx##xx#D#..",
    "..#B###BB###D#..",
    "..#BBBB##BBCD#..",
    "...#BBB##BCD#...",
    "...#B#B#B#CD#...",
    "...#BBBBBBCD#...",
    "....########....",
    "................",
    "................",
    "................",
]

T["crown"] = [
    "................",
    "................",
    "................",
    "..#....##....#..",
    ".#A#..#AB#..#C#.",
    ".#AB##ABBC##BC#.",
    ".#ABBABBBBCBBC#.",
    ".#ABxBBWxBBxCD#.",
    ".#ABBBBBBBBBCD#.",
    ".#abbbbbbbbbbc#.",
    ".#BBCCCCCCCDDD#.",
    "..############..",
    "................",
    "................",
    "................",
    "................",
]

T["sigil"] = [
    "................",
    ".....######.....",
    "...##AABBBB##...",
    "..#AABBxxBBCC#..",
    "..#ABBxBBxBBC#..",
    ".#ABxxxxxxxxBC#.",
    ".#ABBxBBBBxBCD#.",
    ".#ABBBxBBxBBCD#.",
    ".#ABBBxxxxBBCD#.",
    ".#BBBxxBBxxCCD#.",
    "..#BxxBBBBxxD#..",
    "..#BCCCCCCDDD#..",
    "...##CCDDDD##...",
    ".....######.....",
    "................",
    "................",
]

T["eye"] = [
    "................",
    "................",
    "................",
    ".....######.....",
    "...##AABBBB##...",
    "..#AABxxxxBCC#..",
    ".#AABxxyyyxBCC#.",
    "#AABxxy##yxxBCD#",
    "#ABBxxy##zxxCCD#",
    ".#BBBxyzzzxCCD#.",
    "..#BBBxxxxCDD#..",
    "...##CCCDDD##...",
    ".....######.....",
    "................",
    "................",
    "................",
]

for _name, _rows in T.items():
    assert len(_rows) == 16, f"sablona {_name}: {len(_rows)} radku"
    for _i, _r in enumerate(_rows):
        assert len(_r) == 16, f"sablona {_name} radek {_i}: delka {len(_r)}"


def render(template, pal):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(T[template]):
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), pal[ch] + (255,))
    return img


def line(x0, y0, x1, y1):
    pts = []
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        pts.append((x0, y0))
        if x0 == x1 and y0 == y1:
            return pts
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def render_bow(pal, pull):
    """pull: None = klidový stav, 0..2 = snímky natahování (s šípem)."""
    img = render("bow", pal)
    string = pal["b"] + (255,)
    top, bottom = (13, 2), (2, 12)
    if pull is None:
        for p in line(12, 3, 3, 12):
            img.putpixel(p, string)
        return img
    k = 6 + pull * 2                      # jak daleko je tětiva natažená
    grip = (k + 2, k + 2)
    for p in line(top[0], top[1] + 1, grip[0], grip[1]) + line(bottom[0] + 1, bottom[1], grip[0], grip[1]):
        img.putpixel(p, string)
    shaft, tip = (120, 86, 52, 255), (214, 214, 222, 255)
    start = (grip[0] - 1, grip[1] - 1)
    n = 9 - pull
    for i in range(n):
        x, y = start[0] - i, start[1] - i
        if 0 <= x < 16 and 0 <= y < 16:
            img.putpixel((x, y), tip if i >= n - 2 else shaft)
    img.putpixel(grip, (235, 235, 235, 255))  # letky
    return img


# ----------------------------------------------------------------------------
#  Textury brnění na těle (64x32) a skiny (64x64)
# ----------------------------------------------------------------------------
def box_faces(ox, oy, w, d, h):
    return {
        "top": (ox + d, oy, w, d),
        "bottom": (ox + d + w, oy, w, d),
        "right": (ox, oy + d, d, h),
        "front": (ox + d, oy + d, w, h),
        "left": (ox + d + w, oy + d, d, h),
        "back": (ox + d + w + d, oy + d, w, h),
    }


def paint_box(img, ox, oy, w, d, h, fn):
    for name, (fx, fy, fw, fh) in box_faces(ox, oy, w, d, h).items():
        for v in range(fh):
            for u in range(fw):
                c = fn(name, u, v, fw, fh)
                if c is not None:
                    img.putpixel((fx + u, fy + v), tuple(c) + (255,))


HEAD = (0, 0, 8, 8, 8)
BODY = (16, 16, 8, 4, 12)
ARM = (40, 16, 4, 4, 12)
LEG = (0, 16, 4, 4, 12)
# levá ruka a noha existují jen ve skinech 64x64
ARM_L = (32, 48, 4, 4, 12)
LEG_L = (16, 48, 4, 4, 12)


class Mat:
    """Stínování materiálu - vrací barvu z rampy podle pozice a stylu."""

    def __init__(self, style, colors, seed):
        self.style = style
        self.m = ramp5(colors[0])
        self.t = ramp3(colors[1])
        self.a = ramp3(colors[2])
        self.dark = outline_of(colors[0])
        self.rng = random.Random(seed)

    def idx(self, face, u, v, w, h):
        i = 2
        if face == "top":
            i = 1
        elif face == "bottom":
            i = 3
        else:
            if v < max(1, h // 4):
                i = 1
            elif v >= h - max(1, h // 5):
                i = 3
            if u == 0 or u == w - 1:
                i += 1
        s = self.style
        r = self.rng.random()
        if s == "plate":
            if face in ("front", "back") and u == 1:
                i -= 1
            if r < 0.08:
                i += 1
        elif s == "leather":
            if r < 0.14:
                i += 1
            elif r > 0.9:
                i -= 1
        elif s == "robe":
            if u % 3 == 2:
                i += 1
            if r < 0.06:
                i -= 1
        elif s == "scale":
            if v % 2 == 0 and (u + (v // 2)) % 2 == 0:
                i -= 1
            elif v % 2 == 1:
                i += 0 if r < 0.6 else 1
        elif s == "stone":
            if r < 0.2:
                i += 1
            elif r > 0.82:
                i -= 1
            if r < 0.03:
                return self.dark
        return self.m[max(0, min(4, i))]

    def __call__(self, face, u, v, w, h):
        return self.idx(face, u, v, w, h)


def paint_head(img, mat, face):
    """Helma. face=None -> helma hráče (obličej zůstane vidět); jinak plná maska bosse."""
    style = mat.style
    ftype = face.get("type") if face else None
    skin = ramp5(face["skin"]) if face and face.get("skin") else None
    eyes = hex_rgb(face["eyes"]) if face and face.get("eyes") else None
    crown = ramp3(face["crown"]) if face and face.get("crown") else None
    horns = hex_rgb(face["horns"]) if face and face.get("horns") else None
    hair = ramp3(face["hair"]) if face and face.get("hair") else None
    rng = random.Random(str(face) + style)
    dark = (18, 14, 20)

    def skin_px(u, v, w, h, f):
        i = 2
        if f == "top":
            i = 1
        if f == "bottom" or v >= h - 1:
            i = 3
        if rng.random() < 0.1:
            i += 1
        return skin[min(4, i)]

    def fn(f, u, v, w, h):
        # ---------------- helmy hráčů ----------------
        if ftype is None:
            if f == "bottom":
                return None
            if style == "plate":
                c = mat(f, u, v, w, h)
                if f == "front":
                    if v in (3, 4) and 1 <= u <= 6:
                        return None if v == 3 else mat.dark   # průzor - oči hráče vidět
                    if v >= 5 and u in (2, 5):
                        return mat.dark                        # větrací otvory
                    if u in (3, 4) and v <= 2:
                        return mat.m[0]                        # hřeben
                if v == 7 and f != "top":
                    return mat.t[1]
                return c
            if style == "robe":
                if f == "front":
                    if v <= 1 or (u in (0, 7) and v <= 5):
                        return mat.t[1] if v == 1 else mat(f, u, v, w, h)
                    return None
                c = mat(f, u, v, w, h)
                if f in ("left", "right") and v == 7:
                    return mat.t[1]
                return c
            # leather / scale / stone - otevřená přilba
            if f == "front":
                if v <= 1:
                    return mat.t[1] if v == 1 else mat(f, u, v, w, h)
                if u in (0, 7) and v <= 6:
                    return mat(f, u, v, w, h)
                return None
            c = mat(f, u, v, w, h)
            if f != "top" and v == 6:
                return mat.t[1]
            if f != "top" and v == 7:
                return None
            return c

        # ---------------- masky mobů a bossů (zakryjí celou hlavu) ----------------
        hood = face.get("hood")
        if ftype in ("skin", "skull"):
            base = skin_px(u, v, w, h, f)
        elif ftype == "golem":
            base = mat(f, u, v, w, h)
        else:
            base = mat(f, u, v, w, h)

        if hood and f != "front" and f != "bottom":
            base = mat(f, u, v, w, h)
        if hair and f in ("top", "back"):
            base = hair[1 if (u + v) % 3 else 0]
        if hair and f in ("left", "right") and v <= 4:
            base = hair[1]
        if hair and f == "front" and v == 0:
            base = hair[1]

        if f == "front":
            if ftype == "skin":
                if v == 2 and u in (1, 2, 5, 6):
                    base = skin[4]                      # obočí
                if v == 3 and u in (1, 2, 5, 6):
                    base = eyes
                if v == 3 and u in (2, 5):
                    base = dark if face.get("ears") else eyes
                if v == 4 and u in (3, 4):
                    base = skin[3]                      # nos
                if v == 6 and 2 <= u <= 5:
                    base = dark                         # ústa
                if v == 6 and u in (2, 5) and face.get("ears"):
                    base = (240, 236, 220)              # tesáky
            elif ftype == "skull":
                if v in (2, 3) and u in (1, 2, 5, 6):
                    base = dark
                if v == 3 and u in (2, 5):
                    base = eyes
                if v == 4 and u in (3, 4):
                    base = dark
                if v == 6 and 1 <= u <= 6:
                    base = dark if u % 2 == 0 else (236, 232, 214)
                if v == 7:
                    base = skin[3]
                if hood and v == 0:
                    base = mat(f, u, v, w, h)
            elif ftype == "mask":
                if v == 3 and 1 <= u <= 6:
                    base = skin[2] if skin else mat.dark
                if v == 3 and u in (2, 5):
                    base = eyes
                if v in (5, 6) and u % 2 == 1:
                    base = mat.m[3]
            elif ftype == "visor":
                if v == 3 and 1 <= u <= 6:
                    base = dark
                if v == 3 and u in (1, 2, 5, 6):
                    base = eyes
                if v <= 2 and u in (3, 4):
                    base = mat.m[0]
                if v >= 5 and u in (1, 3, 4, 6):
                    base = mat.dark
                if v == 7:
                    base = mat.t[1]
            elif ftype == "golem":
                if v == 3 and u in (1, 2, 5, 6):
                    base = eyes
                if v == 2 and 1 <= u <= 6:
                    base = mat.m[4]
                if v == 6 and 2 <= u <= 5:
                    base = mat.dark
                if (u, v) in ((3, 0), (3, 1), (4, 1), (6, 5), (0, 6)):
                    base = mat.dark                     # praskliny

        if face.get("ears") and f in ("left", "right") and 2 <= v <= 4 and 2 <= u <= 5:
            base = skin[1] if (u + v) % 2 else skin[3]
        if horns and ((f in ("left", "right") and v <= 2 and 2 <= u <= 5)
                      or (f == "top" and u in (0, 1, 6, 7) and 2 <= v <= 5)):
            base = horns
        if crown:
            if f in ("front", "back", "left", "right") and v == 0:
                base = crown[0] if u % 2 == 0 else crown[1]
            if f == "top" and (u in (0, 7) or v in (0, 7)):
                base = crown[1]
            if f == "front" and v == 0 and u in (3, 4):
                base = mat.a[0]
        return base

    paint_box(img, *HEAD, fn)


def paint_chest(img, mat, full=False):
    style = mat.style

    def body(f, u, v, w, h):
        c = mat(f, u, v, w, h)
        if f in ("top", "bottom"):
            return c
        if v == 0:
            return mat.t[0]
        if style == "robe":
            if f == "front" and u in (3, 4):
                c = mat.t[1] if u == 3 else mat.t[2]
                if v == 1:
                    c = mat.a[0]
            return c
        if f == "front":
            if 3 <= u <= 4 and 3 <= v <= 4:
                return mat.a[0] if v == 3 else mat.a[1]
            if style == "plate" and v in (6, 8) and 1 <= u <= 6:
                return mat.m[3]
        if v == 11:
            return mat.t[1]
        return c

    def arm(f, u, v, w, h):
        c = mat(f, u, v, w, h)
        if f in ("top", "bottom"):
            return mat.t[0] if f == "top" and style != "robe" else c
        if style == "robe":
            if v >= 10:
                return mat.t[1]
            return c
        if v <= 2:
            return mat.t[1] if v == 2 else mat.m[1]
        if v == 8:
            return mat.t[1]
        if v > 8:
            return mat.m[3]
        return c

    paint_box(img, *BODY, body)
    paint_box(img, *ARM, arm)


def paint_boots(img, mat):
    start = 9 if mat.style == "robe" else 7

    def fn(f, u, v, w, h):
        if f == "top":
            return None
        if f == "bottom":
            return mat.m[4]
        if v < start:
            return None
        if v == start:
            return mat.t[1]
        return mat(f, u, v, w, h)

    paint_box(img, *LEG, fn)


def paint_leggings(img, mat):
    style = mat.style

    def leg(f, u, v, w, h):
        if f in ("top", "bottom"):
            return mat.m[3]
        c = mat(f, u, v, w, h)
        if style == "robe":
            return mat.t[1] if v == 11 else c
        if v == 5 and f == "front":
            return mat.t[1]
        return c

    def waist(f, u, v, w, h):
        if f in ("top", "bottom"):
            return None
        first = 6 if style == "robe" else 7
        if v < first:
            return None
        if v == first:
            if f == "front" and u in (3, 4):
                return mat.a[0]
            return mat.t[1]
        return mat(f, u, v, w, h)

    paint_box(img, *LEG, leg)
    paint_box(img, *BODY, waist)


def make_equipment(set_id, spec):
    colors = spec["colors"]
    face = spec.get("face")
    l1 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    l2 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    paint_head(l1, Mat(spec["style"], colors, set_id + "h"), face)
    paint_chest(l1, Mat(spec["style"], colors, set_id + "c"))
    paint_boots(l1, Mat(spec["style"], colors, set_id + "b"))
    paint_leggings(l2, Mat(spec["style"], colors, set_id + "l"))
    return l1, l2


# ---- skiny NPC ----
NPC_SKINS = {
    "elder": dict(skin="#e0b090", hair="#e8e8e8", hairstyle="long", beard="#e8e8e8", eyes="#3a6ad0",
                  top="#6a4a8a", bottom="#6a4a8a", shoes="#3a2a1a", trim="#e8c24a", robe=True),
    "merchant": dict(skin="#d8a47a", hair="#5a3a1a", hairstyle="hat", hat="#2f7a4a", eyes="#3a2a1a",
                     top="#f0e8d0", vest="#2f7a4a", bottom="#5a4a3a", shoes="#2a1a10", trim="#e8c24a"),
    "blacksmith": dict(skin="#b8855a", hair=None, hairstyle="bald", beard="#3a2a1a", eyes="#2a1a10",
                       top="#8a8a8a", apron="#6a4a2a", bottom="#3a3a40", shoes="#1a1a1a", trim="#c8c8d0",
                       short_sleeves=True),
    "trainer": dict(skin="#c89870", hair="#2a2a2a", hairstyle="short", eyes="#2a1a10",
                    top="#8a2a2a", tabard="#c8c8d0", bottom="#3a3a48", shoes="#2a2a2a", trim="#e8c24a"),
    "healer": dict(skin="#f0c8a8", hair="#e8c860", hairstyle="long", eyes="#3a8a5a",
                   top="#f4f4f0", cross="#d03a3a", bottom="#f4f4f0", shoes="#a08a6a", trim="#e8c24a", robe=True),
    "alchemist": dict(skin="#d8b090", hair="#6a3a8a", hairstyle="hood", hat="#1f6a6a", eyes="#7a3ad0",
                      top="#1f6a6a", bottom="#1f6a6a", shoes="#2a2a2a", trim="#a0e860", robe=True),
    "guard": dict(skin="#c89870", hair="#8a8a90", hairstyle="hat", hat="#9aa0ac", eyes="#2a1a10",
                  top="#9aa0ac", tabard="#2a4a9a", bottom="#5a6068", shoes="#3a3a40", trim="#e8c24a"),
    "hunter": dict(skin="#c89870", hair="#3f6a2f", hairstyle="hood", hat="#3f6a2f", beard="#5a3a1a", eyes="#3a5a2a",
                   top="#4f7a3a", bottom="#5a4a30", shoes="#3a2a1a", trim="#c9a86a"),
}


def make_skin(name, s):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    rng = random.Random(name)
    skin = ramp5(s["skin"])
    hair = ramp3(s["hair"]) if s.get("hair") else None
    hat = ramp3(s["hat"]) if s.get("hat") else None
    beard = ramp3(s["beard"]) if s.get("beard") else None
    top = ramp5(s["top"])
    bottom = ramp5(s["bottom"])
    shoes = ramp3(s["shoes"])
    trim = ramp3(s["trim"])
    eyes = hex_rgb(s["eyes"])
    hs = s.get("hairstyle", "short")

    def noisy(r, i):
        if rng.random() < 0.12:
            i += 1
        return r[max(0, min(len(r) - 1, i))]

    def head(f, u, v, w, h):
        c = noisy(skin, 2 if f != "bottom" else 3)
        cover = hat if hs in ("hat", "hood") else hair
        if cover:
            if f == "top":
                c = noisy(cover, 0)
            elif f == "back":
                rows = 8 if hs in ("long", "hood") else (2 if hs == "hat" else 5)
                if v < rows:
                    c = noisy(cover, 1)
            elif f in ("left", "right"):
                rows = 8 if hs in ("long", "hood") else (2 if hs == "hat" else 3)
                back_half = (u < 4) if f == "right" else (u >= 4)
                if v < 2 or (v < rows and (back_half or hs == "hood")):
                    c = noisy(cover, 1)
            elif f == "front":
                if v < (2 if hs != "bald" else 0):
                    c = noisy(cover, 1)
                if hs == "hood" and u in (0, 7):
                    c = noisy(cover, 2)
        if hs == "hat" and hair and f != "top" and f != "bottom" and v == 2 and f != "front":
            c = hair[1]                                   # vlasy pod kloboukem
        if hs == "hat" and f in ("front", "back", "left", "right") and v == 1:
            c = trim[1]                                   # stuha klobouku
        if f == "front":
            if v == 4 and u in (1, 6):
                c = (245, 245, 245)
            if v == 4 and u in (2, 5):
                c = eyes
            if v == 3 and u in (1, 2, 5, 6):
                c = skin[3] if not hair else hair[2]      # obočí
            if v == 5 and u in (3, 4):
                c = skin[3]
            if beard:
                if v >= 5 and not (v == 5 and u in (3, 4)):
                    c = noisy(beard, 1)
                if v == 6 and u in (3, 4):
                    c = (90, 50, 44)
            elif v == 6 and 3 <= u <= 4:
                c = (150, 84, 70)
        elif beard and f in ("left", "right") and v >= 5:
            front_half = (u >= 4) if f == "right" else (u < 4)
            if front_half:
                c = noisy(beard, 1)
        return c

    def body(f, u, v, w, h):
        i = 2 + (1 if u in (0, w - 1) and f in ("front", "back") else 0) + (1 if v >= 10 else 0)
        c = noisy(top, i)
        if f in ("top", "bottom"):
            return noisy(top, 1 if f == "top" else 3)
        if s.get("vest"):
            vest = ramp3(s["vest"])
            if f != "front" or u in (0, 1, 6, 7):
                c = noisy(vest, 1)
            if f == "front" and u in (2, 5):
                c = trim[1]
        if s.get("apron"):
            ap = ramp3(s["apron"])
            if f == "front" and 1 <= u <= 6 and v >= 2:
                c = noisy(ap, 1)
            if f == "front" and u in (1, 6) and v < 2:
                c = ap[2]
        if s.get("tabard"):
            tb = ramp3(s["tabard"])
            if f in ("front", "back") and 2 <= u <= 5:
                c = noisy(tb, 1)
                if f == "front" and 3 <= u <= 4 and 3 <= v <= 5:
                    c = trim[0]
        if s.get("cross") and f == "front":
            if (3 <= u <= 4 and 2 <= v <= 7) or (2 <= u <= 5 and 4 <= v <= 5):
                c = hex_rgb(s["cross"])
        if s.get("robe"):
            if f == "front" and u in (3, 4) and not s.get("cross"):
                c = trim[1]
            if v == 0:
                c = trim[0]
        if v in (9, 10) and not s.get("apron"):
            c = trim[2] if v == 10 else shoes[1]          # opasek
            if f == "front" and u in (3, 4) and v == 9:
                c = trim[0]
        return c

    def arm(f, u, v, w, h):
        if f == "top":
            return noisy(top, 1)
        if f == "bottom":
            return skin[3]
        sleeve_end = 4 if s.get("short_sleeves") else 9
        if v >= 9 or v >= sleeve_end:
            if v >= 9:
                return noisy(skin, 2 if v < 11 else 3)
            return noisy(skin, 2)
        if v == sleeve_end - 1:
            return trim[1]
        return noisy(top, 2 + (1 if u == 0 else 0))

    def leg(f, u, v, w, h):
        if f == "top":
            return noisy(bottom, 2)
        if f == "bottom":
            return shoes[2]
        if s.get("robe"):
            if v == 10:
                return trim[1]
            if v == 11:
                return shoes[1]
            return noisy(bottom, 2 + (1 if u % 3 == 2 else 0))
        if v >= 9:
            return noisy(shoes, 1 if v < 11 else 2)
        return noisy(bottom, 2 + (1 if u == 0 else 0))

    paint_box(img, *HEAD, head)
    paint_box(img, *BODY, body)
    paint_box(img, *ARM, arm)
    paint_box(img, *ARM_L, arm)
    paint_box(img, *LEG, leg)
    paint_box(img, *LEG_L, leg)
    return img


# ----------------------------------------------------------------------------
#  HUD ikony (font)
# ----------------------------------------------------------------------------
HUD = {
    "heart": ("", {"r": (232, 48, 64), "d": (150, 20, 40), "w": (255, 190, 190), "#": (40, 8, 14)}, [
        ".##.##..",
        "#wr#rr#.",
        "#rrrrd#.",
        "#rrrrd#.",
        ".#rrd#..",
        "..#d#...",
        "...#....",
        "........",
    ]),
    "mana": ("", {"r": (64, 150, 255), "d": (30, 80, 190), "w": (200, 230, 255), "#": (8, 20, 60)}, [
        "...#....",
        "..#r#...",
        "..#r#...",
        ".#wrd#..",
        "#wrrrd#.",
        "#rrrdd#.",
        ".#ddd#..",
        "..###...",
    ]),
    "coin": ("", {"r": (250, 200, 60), "d": (190, 130, 20), "w": (255, 245, 180), "#": (70, 40, 0)}, [
        "..###...",
        ".#wrr#..",
        "#wrdrd#.",
        "#rrdrd#.",
        "#rrdrd#.",
        "#rdddd#.",
        ".#ddd#..",
        "..###...",
    ]),
    "sword": ("", {"r": (220, 224, 232), "d": (130, 90, 50), "w": (255, 255, 255), "#": (30, 30, 40)}, [
        ".....##.",
        "....#w#.",
        "...#wr#.",
        "#.#wr#..",
        "##wr#...",
        ".#d#....",
        "#d##....",
        "##......",
    ]),
    "shield": ("", {"r": (120, 150, 210), "d": (60, 80, 150), "w": (210, 225, 250), "#": (16, 22, 50)}, [
        "#######.",
        "#wrrrd#.",
        "#wrrrd#.",
        "#rrrdd#.",
        "#rrrdd#.",
        ".#rdd#..",
        "..#d#...",
        "...#....",
    ]),
    "star": ("", {"r": (130, 230, 110), "d": (50, 150, 60), "w": (220, 255, 200), "#": (10, 50, 14)}, [
        "...#....",
        "..#w#...",
        "###r###.",
        "#wrrrd#.",
        ".#rrd#..",
        ".#rdr#..",
        "#d#.#d#.",
        "##...##.",
    ]),
}


def make_hud_icon(colors, rows):
    img = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), colors[ch] + (255,))
    return img


# ----------------------------------------------------------------------------
#  Sestavení packu
# ----------------------------------------------------------------------------
ARMOR_ICONS = {
    "plate": ("helmet", "chestplate", "leggings", "boots"),
    "leather": ("helmet", "chestplate", "leggings", "boots"),
    "scale": ("helmet", "chestplate", "leggings", "boots"),
    "stone": ("helmet", "chestplate", "leggings", "boots"),
    "robe": ("hood", "robe", "leggings", "boots"),
}
PIECES = ("helmet", "chestplate", "leggings", "boots")
LARGE = {"GREATSWORD", "HAMMER", "SCYTHE", "SPEAR", "STAFF"}
HANDHELD = {"SWORD", "AXE", "DAGGER", "WAND"} | LARGE


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def save_png(path, img):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)


def model_ref(name):
    return {"type": "minecraft:model", "model": f"{NS}:item/{name}"}


def build(sheet_path=None):
    data = yaml.safe_load(ITEMS_YML.read_text(encoding="utf-8"))
    sets, items = data["sets"], data["items"]

    if OUT.exists():
        shutil.rmtree(OUT)
    assets = OUT / "assets" / NS
    tex_item = assets / "textures/item"
    icons = {}   # id -> obrázek (pro náhled)

    # --- vlastní rodič pro velké zbraně (větší v ruce) ---
    write_json(assets / "models/item/handheld_large.json", {
        "parent": "minecraft:item/handheld",
        "display": {
            "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 6.5, 1.25], "scale": [1.2, 1.2, 1.2]},
            "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 6.5, 1.25], "scale": [1.2, 1.2, 1.2]},
            "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 4.2, 1.13], "scale": [0.85, 0.85, 0.85]},
            "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 4.2, 1.13], "scale": [0.85, 0.85, 0.85]},
        },
    })

    def simple_item(item_id, img, parent="minecraft:item/generated"):
        save_png(tex_item / f"{item_id}.png", img)
        write_json(assets / f"models/item/{item_id}.json",
                   {"parent": parent, "textures": {"layer0": f"{NS}:item/{item_id}"}})
        write_json(assets / f"items/{item_id}.json", {"model": model_ref(item_id)})
        icons[item_id] = img

    # --- předměty ---
    for item_id, spec in items.items():
        pal = palette(spec["colors"])
        typ = spec["type"]
        if typ == "BOW":
            frames = {item_id: render_bow(pal, None)}
            for i in range(3):
                frames[f"{item_id}_pulling_{i}"] = render_bow(pal, i)
            for name, img in frames.items():
                save_png(tex_item / f"{name}.png", img)
                write_json(assets / f"models/item/{name}.json",
                           {"parent": "minecraft:item/bow", "textures": {"layer0": f"{NS}:item/{name}"}})
            write_json(assets / f"items/{item_id}.json", {"model": {
                "type": "minecraft:condition",
                "property": "minecraft:using_item",
                "on_false": model_ref(item_id),
                "on_true": {
                    "type": "minecraft:range_dispatch",
                    "property": "minecraft:use_duration",
                    "scale": 0.05,
                    "entries": [
                        {"threshold": 0.65, "model": model_ref(f"{item_id}_pulling_1")},
                        {"threshold": 0.9, "model": model_ref(f"{item_id}_pulling_2")},
                    ],
                    "fallback": model_ref(f"{item_id}_pulling_0"),
                },
            }})
            icons[item_id] = frames[item_id]
            icons[item_id + "_pulling_2"] = frames[f"{item_id}_pulling_2"]
            continue
        parent = "minecraft:item/generated"
        if typ in LARGE:
            parent = f"{NS}:item/handheld_large"
        elif typ in HANDHELD:
            parent = "minecraft:item/handheld"
        simple_item(item_id, render(spec["icon"], pal), parent)

    # --- sety brnění ---
    equipment_sheets = {}
    for set_id, spec in sets.items():
        l1, l2 = make_equipment(set_id, spec)
        save_png(assets / f"textures/entity/equipment/humanoid/{set_id}.png", l1)
        save_png(assets / f"textures/entity/equipment/humanoid_leggings/{set_id}.png", l2)
        write_json(assets / f"equipment/{set_id}.json", {"layers": {
            "humanoid": [{"texture": f"{NS}:{set_id}"}],
            "humanoid_leggings": [{"texture": f"{NS}:{set_id}"}],
        }})
        equipment_sheets[set_id] = (l1, l2)
        pal = palette(spec["colors"])
        for piece, icon in zip(PIECES, ARMOR_ICONS[spec["style"]]):
            simple_item(f"{set_id}_{piece}", render(icon, pal))

    # --- skiny NPC ---
    skins = {}
    for name, spec in NPC_SKINS.items():
        img = make_skin(name, spec)
        save_png(assets / f"textures/entity/npc/{name}.png", img)
        skins[name] = img

    # --- HUD font ---
    providers = []
    for name, (char, colors, rows) in HUD.items():
        save_png(assets / f"textures/font/{name}.png", make_hud_icon(colors, rows))
        providers.append({"type": "bitmap", "file": f"{NS}:font/{name}.png", "ascent": 7, "height": 8, "chars": [char]})
    write_json(assets / "font/icons.json", {"providers": providers})

    # --- pack.mcmeta + ikona ---
    write_json(OUT / "pack.mcmeta", {"pack": {
        "description": "RPGCore - vlastní předměty, brnění, bossové a NPC",
        "pack_format": PACK_FORMAT,
        "supported_formats": [PACK_FORMAT_MIN, PACK_FORMAT_MAX],
        "min_format": PACK_FORMAT_MIN,
        "max_format": PACK_FORMAT_MAX,
    }})
    logo = Image.new("RGBA", (64, 64), (24, 18, 38, 255))
    big = icons["dragon_slayer"].resize((48, 48), Image.NEAREST)
    logo.alpha_composite(big, (8, 8))
    save_png(OUT / "pack.png", logo)

    # --- zip (deterministický, aby se hash neměnil bez důvodu) ---
    files = sorted(p for p in OUT.rglob("*") if p.is_file())
    for target in (ZIP_PLUGIN, ZIP_DIST):
        target.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
            for p in files:
                info = zipfile.ZipInfo(p.relative_to(OUT).as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                z.writestr(info, p.read_bytes())

    print(f"Hotovo: {len(items)} předmětů, {len(sets)} setů, {len(NPC_SKINS)} skinů NPC, {len(files)} souborů")
    print(f"  {ZIP_PLUGIN.relative_to(ROOT)}  ({ZIP_PLUGIN.stat().st_size // 1024} kB)")

    if sheet_path:
        make_sheet(sheet_path, icons, equipment_sheets, skins)


def make_sheet(path, icons, equipment, skins):
    """Náhledový arch - ikony 4x, textury brnění 3x, skiny 3x."""
    scale, cols = 4, 20
    cell = 16 * scale + 6
    rows = (len(icons) + cols - 1) // cols
    eq_scale = 3
    eq_cols = 3
    eq_rows = (len(equipment) + eq_cols - 1) // eq_cols
    eq_w, eq_h = 64 * eq_scale * 2 + 14, 32 * eq_scale + 8
    sk_scale = 3
    width = max(cols * cell, eq_cols * eq_w, len(skins) * (64 * sk_scale + 8)) + 12
    height = rows * cell + eq_rows * eq_h + 64 * sk_scale + 40
    sheet = Image.new("RGBA", (width, height), (58, 58, 66, 255))
    for i, (_name, img) in enumerate(icons.items()):
        x, y = 6 + (i % cols) * cell, 6 + (i // cols) * cell
        sheet.alpha_composite(img.resize((16 * scale, 16 * scale), Image.NEAREST), (x, y))
    y0 = rows * cell + 16
    for i, (_name, (l1, l2)) in enumerate(equipment.items()):
        x, y = 6 + (i % eq_cols) * eq_w, y0 + (i // eq_cols) * eq_h
        sheet.alpha_composite(l1.resize((64 * eq_scale, 32 * eq_scale), Image.NEAREST), (x, y))
        sheet.alpha_composite(l2.resize((64 * eq_scale, 32 * eq_scale), Image.NEAREST), (x + 64 * eq_scale + 4, y))
    y1 = y0 + eq_rows * eq_h + 10
    for i, (_name, img) in enumerate(skins.items()):
        sheet.alpha_composite(img.resize((64 * sk_scale, 64 * sk_scale), Image.NEAREST), (6 + i * (64 * sk_scale + 8), y1))
    sheet.save(path)
    print(f"  náhled: {path}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", help="uloží náhledový arch všech textur do PNG")
    build(ap.parse_args().sheet)
