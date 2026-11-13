#!/usr/bin/env python3
"""Kontrola, že na sebe yml soubory správně odkazují a že resource pack má všechny textury.
Použití: python3 tools/validate.py"""
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "plugin/src/main/resources"
PACK = ROOT / "resourcepack/assets/rpg"
VANILLA_OK = {"ARROW", "BOW", "IRON_SWORD"}
errors = []


def load(name):
    return yaml.safe_load((RES / name).read_text(encoding="utf-8"))


def err(msg):
    errors.append(msg)


data = load("items.yml")
sets, items = data["sets"], dict(data["items"])
for sid, s in sets.items():
    if not s.get("costume"):
        for piece in ("helmet", "chestplate", "leggings", "boots"):
            items[f"{sid}_{piece}"] = {"type": piece.upper()}
mobs, bosses, npcs = load("mobs.yml"), load("bosses.yml"), load("npcs.yml")
quests, shops, crates, rewards = load("quests.yml"), load("shops.yml"), load("crates.yml"), load("rewards.yml")


def item(ref, where):
    if ref not in items and ref not in VANILLA_OK:
        err(f"{where}: neznámý předmět '{ref}'")


for mid, m in {**mobs, **bosses}.items():
    if m.get("costume") and m["costume"] not in sets:
        err(f"mob {mid}: neznámý kostým '{m['costume']}'")
    if m.get("weapon"):
        item(m["weapon"], f"mob {mid} weapon")
    for d in m.get("drops", []) + m.get("loot", []):
        item(d["item"], f"mob {mid} drop")
for bid, b in bosses.items():
    for ph in b["phases"]:
        for a in ph["abilities"]:
            if a not in b["abilities"]:
                err(f"boss {bid}: fáze odkazuje na neznámou schopnost '{a}'")
    for key, a in b["abilities"].items():
        if a["type"] == "SUMMON" and a.get("mob") not in mobs:
            err(f"boss {bid}: schopnost {key} vyvolává neznámého moba '{a.get('mob')}'")
for iid, it in items.items():
    if it.get("boss") and it["boss"] not in bosses:
        err(f"předmět {iid}: neznámý boss '{it['boss']}'")
    if it.get("type") == "CRATE" and iid not in crates:
        err(f"bedna {iid} nemá obsah v crates.yml")
for qid, q in quests.items():
    if q.get("giver") not in npcs:
        err(f"úkol {qid}: neznámý zadavatel '{q.get('giver')}'")
    for r in q.get("requires", []):
        if r not in quests:
            err(f"úkol {qid}: vyžaduje neznámý úkol '{r}'")
    for o in q["objectives"]:
        if o["type"] == "KILL" and o["target"] not in mobs and o["target"] not in bosses:
            err(f"úkol {qid}: neznámý cíl '{o['target']}'")
        if o["type"] == "COLLECT":
            item(o["item"], f"úkol {qid}")
        if o["type"] == "TALK" and o["npc"] not in npcs:
            err(f"úkol {qid}: neznámé NPC '{o['npc']}'")
    for i in (q.get("rewards") or {}).get("items", {}):
        item(i, f"úkol {qid} odměna")
for n, npc in npcs.items():
    if npc["role"] == "SHOP" and npc.get("shop") not in shops["shops"]:
        err(f"NPC {n}: neznámý obchod '{npc.get('shop')}'")
    if not (PACK / f"textures/entity/npc/{npc['skin']}.png").exists():
        err(f"NPC {n}: chybí skin '{npc['skin']}'")
for sid, s in shops["shops"].items():
    for e in s["items"]:
        item(e["item"], f"obchod {sid}")
for rid, r in shops["recipes"].items():
    item(r["result"], f"recept {rid}")
    for i in r["ingredients"]:
        item(i, f"recept {rid}")
for cid, c in crates.items():
    if items.get(cid, {}).get("type") != "CRATE":
        err(f"crates.yml: '{cid}' není předmět typu CRATE")
    for e in c["loot"]:
        if "item" in e:
            item(e["item"], f"bedna {cid}")
for r in rewards["daily"] + list(rewards["levels"].values()) + [rewards["playtime"]]:
    for i in r.get("items", {}):
        item(i, "rewards.yml")

# ---- resource pack: každý předmět má definici, model i texturu ----
for iid in items:
    d = PACK / f"items/{iid}.json"
    if not d.exists():
        err(f"pack: chybí items/{iid}.json")
        continue
    text = d.read_text()
    for ref in set(__import__("re").findall(r'"rpg:item/([a-z0-9_]+)"', text)):
        model = PACK / f"models/item/{ref}.json"
        if not model.exists():
            err(f"pack: chybí model {ref}")
            continue
        tex = json.loads(model.read_text())["textures"]["layer0"].split(":item/")[1]
        if not (PACK / f"textures/item/{tex}.png").exists():
            err(f"pack: chybí textura {tex}")
for sid in sets:
    for layer in ("humanoid", "humanoid_leggings"):
        if not (PACK / f"textures/entity/equipment/{layer}/{sid}.png").exists():
            err(f"pack: chybí textura brnění {layer}/{sid}")
    if not (PACK / f"equipment/{sid}.json").exists():
        err(f"pack: chybí equipment/{sid}.json")
for f in (ROOT / "resourcepack").rglob("*.json"):
    json.loads(f.read_text(encoding="utf-8"))
json.loads((ROOT / "resourcepack/pack.mcmeta").read_text(encoding="utf-8"))

print(f"{len(items)} předmětů, {len(mobs)} mobů, {len(bosses)} bossů, {len(quests)} úkolů, "
      f"{len(shops['recipes'])} receptů, {len(crates)} beden")
if errors:
    print(f"\n{len(errors)} CHYB:")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("Vše v pořádku.")
