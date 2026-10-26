# METAcraft Skins

Claimable, tradeable item skins for METAcraft: server-side, Fabric + Polymer, Minecraft 26.3. Players
need nothing but the resource pack the server sends them.

An admin hands out **Skin Tokens**. A player right-clicks one and confirms, which **Claims** the skin:
they become its **Owner**, and the token is used up. `/skins` opens the **Skin Menu**, where they put the
skin on any item of its **Kind** they carry, which makes it a **Skinned Item**. A skin has a **Look** (a
Blockbench model sent in the skins resource pack) and may have **Perks**, small effects that work for
whoever holds or wears the item. Each skin is on one item at a time: applying it elsewhere makes the old
item plain again, wherever it is.

This README is the admin guide. Players get a short summary from the "How skins work" book in the Skin
Menu.

- [Installing](#installing)
- [Files](#files)
- [Writing a skin](#writing-a-skin)
- [Perks](#perks)
- [Reloading and the pack](#reloading-and-the-pack)
- [Settings](#settings)
- [Commands and permissions](#commands-and-permissions)
- [Dormant skins and permanent ids](#dormant-skins-and-permanent-ids)
- [Ownership data](#ownership-data)
- [Moving servers](#moving-servers)
- [How items behave](#how-items-behave)
- [Development](#development)

## Installing

Needs metacraft-core (Polymer and sgui come with it), metacraft-config and Polymer's resource-pack
module. Two mods are optional, and used when present:

- **metacraft-resource-packs:** the skins pack is a pack of its own, served by that mod and sent at join
  with the server's other packs, so adding a skin never changes the main pack's hash. Without it the
  skins' files go into the Polymer pack.

The mod adds no mixins. The pack is required exactly when the server's packs are.

## Files

Everything is under the server's root folder:

```
config/
  metacraft-skins.json5            the settings
  metacraft-skins/skins/<skin-id>/  one folder per skin: skin.json5 and its Blockbench export
skins-data/players/<uuid>.json     who owns what, one file per player
world/metacraft-skins.json         the world's stamp (see "Ownership data")
```

The mod ships no skins: each one is a folder the admin adds, as below.

## Writing a skin

A skin is a folder, `config/metacraft-skins/skins/<skin-id>/`. **The folder name is the skin's id, for
good** (see [Dormant skins and permanent ids](#dormant-skins-and-permanent-ids)). It may only hold `a-z`,
`0-9`, `_`, `-` and `.`.

```
config/metacraft-skins/skins/emerald_pick/
  skin.json5            the definition
  emerald_pick.json     the Blockbench model ("Export > Java Block/Item Model")
  emerald_pick.png      every texture the model uses
  emerald_pick.png.mcmeta   optional: animated textures are copied along
```

Drop the export in as it is, then run `/skins reload`. The mod cleans it up as it builds the pack:

- every texture reference is rewritten by its file name to `skins:item/<skin-id>/<name>`, so
  Blockbench's paths (`minecraft:block/gold_block`, `item/foo`, `foo.png`) all find the `.png` beside the
  model. A reference with no matching `.png` in the folder rejects the skin;
- a missing `particle` texture is filled in with the model's first texture;
- `.png.mcmeta` files are copied with their textures;
- missing display transforms are warned about, never invented. No `gui` (and, on a helmet, no `head`)
  is a prominent warning: the item will look wrong in inventories (or on heads). No `fixed` (item frames)
  or `on_shelf` is a minor one. Models with a `parent` aren't checked; they may inherit them.

### skin.json5

```json5
{
	name: "Emerald Pick",               // plain text, shown on tokens, menus and items
	kind: "pickaxe",                    // the Kind it fits, below
	// materials: ["diamond", "netherite"],   // optional: only these Materials of the Kind
	look: { model: "emerald_pick.json" },
	description: "Mined from a creeper's dream.",   // optional: one gray line
	perks: [ /* optional, see Perks */ ],
}
```

| field | |
| --- | --- |
| `name` | required, plain text |
| `kind` | required: `sword`, `axe`, `pickaxe`, `shovel`, `hoe`, `mace`, `spear`, `helmet`, `chestplate`, `leggings`, `boots` or `elytra` |
| `materials` | optional: a list of one or more of the Kind's Materials, below; leave it out to fit every Material |
| `look` | required: `{ model: "<file>.json" }`, except a spear, which takes `{ icon: "<file>.json", in_hand: "<file>.json" }` |
| `description` | optional, one line |
| `perks` | optional; leave it out for a Look-only skin |

Any other field rejects the skin. The files `look` names must be `.json` files in the skin's folder.

**Kinds.** A Kind is one family of items: a `sword` skin fits any sword, wood to netherite, unless
`materials` limits it. Membership is the vanilla item tag (`#minecraft:swords`, `#minecraft:head_armor`
and so on), so other mods' items count when they are tagged; the mace is anything in
`#minecraft:mace_enchantable` and the elytra is anything that glides from the chest slot. Anything that
stacks never fits, and nor do bows, crossbows, tridents, shields, fishing rods, shears, flint and steel,
brushes and the on-a-stick items. Helmet skins fit all head armour, the turtle shell included.

**Materials.** `materials: ["diamond", "netherite"]` limits a skin to those Materials of its Kind. The
names are nouns, as in vanilla's material ids (`wood` and `gold`, not `wooden` and `golden`):

| Kind | Materials |
| --- | --- |
| `sword`, `axe`, `pickaxe`, `shovel`, `hoe`, `spear` | `wood`, `stone`, `copper`, `iron`, `gold`, `diamond`, `netherite` |
| `chestplate`, `leggings`, `boots` | `leather`, `chainmail`, `copper`, `iron`, `gold`, `diamond`, `netherite` |
| `helmet` | the armour seven, and `turtle` (the turtle shell) |
| `mace`, `elytra` | none: these skins take no `materials` |

An item's Material is read off the item itself: one of the vanilla items above. Any other item of the
Kind (another mod's ruby sword) has no Material the mod knows, so it fits only skins with no
`materials`. Listing every Material of the Kind is allowed, but it isn't the same as leaving the field
out: the full list doesn't fit such an item. Materials aren't part of the Look, so changing them never
changes the pack. Narrowing the list takes the skin off only the items that no longer fit, each when
the server next touches it, and the skin goes Unapplied for its Owner; items that still fit keep it.
Widening the list again before an item is touched leaves it as it was. (Changing `kind` is different:
see [Dormant skins and permanent ids](#dormant-skins-and-permanent-ids).)

**How a Look shows:**

- **Helmets** show their model on the wearer's head, and the helmet keeps its armour and abilities.
  Give the model a `head` display transform.
- **Chestplates, leggings, boots and elytra** change only the look in the hand, in inventories, on the
  ground and in frames. Worn, they look vanilla.
- **Spears** use the `icon` model in inventories, on the ground, in item frames and on shelves, and the
  `in_hand` model everywhere else, with vanilla's `swap_animation_scale` (1.95). Keep the in-hand
  model's display transforms close to vanilla's `item/spear_in_hand`. The charge and jab animations
  are the spear's own.
- **Everything else** (the mace too) uses one model everywhere.

The item's own name (an anvil rename too) and its enchantment glint are never touched.

## Perks

A Perk is a **Trigger** and the effects it sets off:

```json5
perks: [
	{ trigger: "held", effects: [ { type: "potion", effect: "minecraft:haste", level: 1 } ] },
	{ trigger: "block_break", chance: 0.10, effects: [
		{ type: "particle", target: "other", particle: { type: "minecraft:happy_villager" }, count: 12 },
		{ type: "sound", target: "other", sound: "minecraft:block.amethyst_block.chime" } ] },
]
```

| Perk field | |
| --- | --- |
| `trigger` | required: `held`, `worn`, `hit`, `kill`, `block_break` or `hurt` |
| `effects` | required: a list of at least one effect |
| `chance` | optional, 0 to 1 (default 1): rolled each time the event happens. Event Triggers only |
| `cooldown` | optional, seconds (default 0): per player, and follows the holder (see below). Event Triggers only |
| `tooltip` | optional: the Perk's line on tokens, menus and items, in place of the generated one |

**Triggers each Kind allows.** Any other rejects the skin.

| Kind | Triggers |
| --- | --- |
| sword, mace, spear | held, hit, kill |
| axe, pickaxe, shovel, hoe | held, hit, kill, block_break |
| helmet, chestplate, leggings, boots, elytra | worn, hurt |

**When each fires:**

- `held`: while the item is in the main hand. The offhand never counts.
- `worn`: while it's in an armour slot, whatever it looks like (a hat counts).
- `hit`: a melee hit with it in the main hand that does damage: a swing (each mob a sweep catches
  counts), a mace smash, a spear's jab or charge. Arrows, tridents and thorns don't count. The killing
  blow is a hit too: its `hit` Perks fire, then its `kill` Perks.
- `kill`: a melee killing blow with it. Player kills count.
- `block_break`: a block actually broken with it, in survival or adventure. The block that breaks a
  worn-out tool still counts.
- `hurt`: any damage taken while wearing it, from any armour slot. The attacker, if there is one, is
  the target (an arrow's shooter, not the arrow). Damage that kills the wearer fires nothing.
- Event Perks (`hit`, `kill`, `block_break`, `hurt`) never fire for a player in creative or spectator.

`held` and `worn` are **lasting** Triggers; the other four are **events**.

**Effects each Trigger allows.** Any other rejects the skin. `target` is `self` (the holder) or
`other`: the victim on hit or kill, the attacker when hurt, the block's position on block break.

| Trigger | Effects |
| --- | --- |
| held, worn | `potion` on self, `attribute`, `particle` aura around self, occasional `sound` around self |
| hit | `potion` on the target or self, `fire` and `knockback` on the target, `heal` self, `particle` and `sound` at the target |
| hurt | `fire`, `knockback` or `potion` on the attacker; `potion` or `heal` on self; `particle`; `sound`. Self effects still fire when there is no attacker |
| kill, block_break | `potion` on self, `heal` self, `particle` and `sound` at the victim or block |

**Effect fields.** Ids are vanilla ids; values are vanilla data-pack units: health in points (2 points =
1 heart), times in seconds except `interval`, which is in ticks. Potion levels start at 1 (`level: 1` is
Haste I). Any field not listed for its type rejects the skin.

| type | fields | notes |
| --- | --- | --- |
| `potion` | `effect` (id), `level` (default 1), `duration` (seconds), `target` | lasting: no `duration`. Event: `duration` required. `target: "other"` on hit and hurt only |
| `attribute` | `attribute` (id), `amount`, `operation` | lasting only. `operation`: `add_value` (default), `add_multiplied_base`, `add_multiplied_total`. Max health of 4 is +2 hearts |
| `fire` | `duration` (seconds) | sets the target alight. Hit and hurt |
| `knockback` | `strength` (default 0.5), `lift` (default 0) | pushes the target away. Hit and hurt |
| `heal` | `amount` (health points) | heals the holder |
| `particle` | `particle` (vanilla particle options), `count` (default 1), `interval` (ticks, default 20), `target` | `interval` for lasting auras only. `target`: `self` while lasting, `other` on hit, kill and block break, either when hurt |
| `sound` | `sound` (id; may be a resource pack's), `volume` (default 1), `pitch` (default 1), `interval` (ticks, default 100), `target` | `interval` for lasting sounds only. `target` as for `particle` |

Where a type allows only one target, `target` defaults to it (`fire` and `knockback` are always
`other`, `heal` and `attribute` always `self`, and `particle` and `sound` are `other` on hit, kill and
block break); otherwise it defaults to `self`. `particle` takes the same
options as a data pack: `{ type: "minecraft:wax_on" }`, or `{ type: "minecraft:item", item:
"minecraft:sugar" }`. A rejection names the place, as in `perks[0].effects[1].effect: there's no
effect "minecraft:hast"`.

**How Perks behave:**

- Perks work for whoever holds or wears the item, not only its Owner.
- Lasting potions are topped up while the item is in place: their HUD icon shows, without swirl
  particles, and they end the moment the item is put away. They never weaken an effect from elsewhere:
  a stronger or equal one is left alone, and a weaker one comes back afterwards with its time left.
- Attribute Perks from several items add up. Potions don't: the strongest wins, as in vanilla.
- Timed potions use their own level and duration, and reset to full when they fire again: no added
  time, no added levels. A stronger effect the target already has wins, as in vanilla.
- `chance` is rolled each time the event happens. A `cooldown` starts when the Perk fires, and it's the
  player's: they wait it out even if they hand the item over and take it back, while the next holder
  can fire it at once. Cooldowns live in memory, so a restart clears them.
- With `perks-affect-players` off, `other` effects skip a target that is a player (its particles and
  sounds too): only mobs get them. The holder's own effects still fire. With `perks-enabled` off, no
  Perk works and none is listed; Looks stay. See [Settings](#settings).
- There are no set bonuses and no caps: strengths are the admin's call.

**Perk lines.** Tokens, the Skin Menu and Skinned Items list each Perk in blue, written from its
definition: "While held: Haste I", "While worn: +2 max hearts and a particle aura", "On hit (25%, 5s
cooldown): Slowness I on the target for 3s", "On kill: heals 2 hearts". Set `tooltip` to word it yourself.

### Worked examples

```json5
{ name: "Candy Sword", kind: "sword", look: { model: "candy_sword.json" },
	perks: [ { trigger: "hit", chance: 0.25, cooldown: 5, effects: [
		{ type: "potion", target: "other", effect: "minecraft:slowness", level: 1, duration: 3 },
		{ type: "particle", target: "other", particle: { type: "minecraft:item", item: "minecraft:sugar" }, count: 8 } ] } ] }

{ name: "Crown", kind: "helmet", look: { model: "crown.json" },
	perks: [ { trigger: "worn", tooltip: "While worn: +2 max hearts and a golden shimmer", effects: [
		{ type: "attribute", attribute: "minecraft:max_health", amount: 4 },
		{ type: "particle", particle: { type: "minecraft:wax_on" }, count: 1, interval: 20 } ] } ] }

{ name: "Frost Spear", kind: "spear", look: { icon: "frost_spear_icon.json", in_hand: "frost_spear_in_hand.json" } }
```

### What rejects a skin

The whole skin is set aside (Dormant) and the others load as usual. Each rejection is logged with the
file and the reason, shown to whoever ran `/skins reload`, and listed by `/skins list`:

- broken json5, or an unknown field anywhere;
- a missing `name`, `kind` or `look`, an unknown Kind, or the wrong `look` shape for the Kind;
- a `materials` that isn't a list of names, an empty list, an unknown Material (`wooden` and `golden`
  get a hint), a Material the Kind doesn't come in (`turtle` on a sword, `wood` on boots), the same
  Material twice, or any `materials` on a mace or elytra skin;
- a Trigger the Kind doesn't allow, an effect the Trigger doesn't allow, an unknown effect, attribute
  or particle id, or a value out of range;
- a model file that isn't in the folder, or isn't a model;
- a texture reference with no matching `.png`;
- a folder with no `skin.json5`, or a folder name with characters an id can't hold.

## Reloading and the pack

Skins are read at server start and by `/skins reload`, and never by vanilla `/reload`, so reloading data
packs never pushes a resource pack by surprise. `/skins reload`:

1. re-reads every skin folder and the settings file;
2. rebuilds the skins pack;
3. if the pack changed, sends it to everyone online (each gets a short loading screen), and otherwise
   sends nothing;
4. reports to you: how many skins loaded, how many are Dormant and why, every model warning, and
   whether the pack changed (with the start of its SHA-1).

The pack is built the same way every time, so the same skins give the same SHA-1. Only Looks are in
it: **editing a skin's name, description or Perks changes every token and Skinned Item at once, and
doesn't change the pack**, so nobody downloads anything. Adding, removing or changing a model or
texture does. So you choose when players see a loading screen: batch Look changes into one reload.

Delivery: with metacraft-resource-packs the skins pack is a separate pack, sent at join with the
others; the last three builds stay downloadable, so a player who was sent one just before another
reload still gets it. Without metacraft-resource-packs the files go into the Polymer pack, which a
reload rebuilds and re-sends (if Polymer is already building it, run `/skins reload` again once it is
done). The pack is required exactly when the server's packs are (`PolymerResourcePackUtils.isRequired()`).

## Settings

`config/metacraft-skins.json5`. metacraft-config writes it on first start with both keys commented out
(so the defaults, both on, apply); write a key with a value to change it. `/skins reload` re-reads it;
vanilla `/reload` doesn't.

```json5
{
	// Whether Perks that hit a target (on hit, on kill, when hurt) affect players too. Off: mobs only.
	"perks-affect-players": true,
	// Off turns every Perk off; Looks keep working.
	"perks-enabled": true
}
```

**Quote the keys.** They contain `-`, which json5 doesn't allow in a bare key: `{ perks-enabled: false }`
is a syntax error. The error is logged (by metacraft-lib) and the settings stay as they were.

## Commands and permissions

One permission node per command, through Fabric's Permission API (so LuckPerms can grant each), with a
vanilla level to fall back on when no permissions mod is installed. Skin ids tab-complete.

| Command | Node | Default |
| --- | --- | --- |
| `/skins` | `metacraft-skins:menu` | everyone |
| `/skins give <targets> <skin> [count]` | `metacraft-skins:give` | gamemasters |
| `/skins info <player>` | `metacraft-skins:info` | gamemasters |
| `/skins list` | `metacraft-skins:list` | gamemasters |
| `/skins grant <player> <skin>` | `metacraft-skins:grant` | admins |
| `/skins revoke <player> <skin>` | `metacraft-skins:revoke` | admins |
| `/skins reload` | `metacraft-skins:reload` | admins |

- **`/skins`** opens the player's Skin Menu.
- **`give`** gives 1 to 64 Skin Tokens (default 1) to online players. What doesn't fit drops at their feet,
  except for a player in creative, who loses the overflow as with vanilla `/give`. Unknown and Dormant
  ids are refused. You get a confirmation; the players get no message. The item is
  `metacraft-skins:skin_token` with the skin's id in its `metacraft-skins:token` component, so
  `/give @s metacraft-skins:skin_token[metacraft-skins:token="crown"]` should make one too (not tried in
  game yet).
- **`info`** shows what a player owns, with claim dates (UTC) and each skin's state: Applied, Unapplied,
  Applied in another world, or Dormant. It can't say which item carries a skin. A damaged file says so.
- **`list`** shows every skin folder with its Kind and, for a Dormant skin, why it didn't load:
  `candy_sword (sword)`. A skin limited to Materials lists them after its Kind, in the order of the
  Materials table: `crown (helmet: leather, turtle)`.
- **`grant`** makes a player own a skin without a token. Refused if they already own it, or the skin is
  unknown or Dormant. An online player gets the claim message.
- **`revoke`** takes a skin away, Dormant ones too. The item carrying it reverts wherever it is. An online
  player is told "<Skin> was removed from your skins by an admin."
- `info`, `grant` and `revoke` take offline players by name, looked up as `/whitelist` does: the server's
  user cache first, then (in online mode) Mojang.
- There is no force-remove: only the Owner takes a skin off an item. Use `revoke` to take the skin away.

Tokens can come from anywhere that hands out items (a shop, a crate plugin); `/skins give` is the only
source this mod has.

## Dormant skins and permanent ids

A skin is **Dormant** while its folder is missing or its definition fails to load. Nothing about it is
deleted:

- its Owners keep it (the Skin Menu hides it; `/skins info` shows it as Dormant);
- its Skinned Items keep their marker but look vanilla and have no Perks;
- its tokens read "Unknown skin token: <id>" and can't be claimed, and `/skins give` refuses it.

Fix the definition (or put the folder back) and run `/skins reload`, and everything comes back
unchanged.

**Ids are permanent.** The folder name is the id stored in every player file, token and Skinned Item.
Renaming a folder doesn't rename a skin: it makes the old id Dormant for everyone and adds a new skin
nobody owns. There is no rename or alias; change `name` instead, which is free.

**Changing a skin's `kind`** is allowed, to fix a mistake, but it's one-way: the skin becomes Unapplied
for every Owner, for good (even if you change the Kind back), and items of the old Kind go plain.
Owners keep the skin and can apply it to an item of the new Kind.

## Ownership data

`skins-data/players/<uuid>.json`, one readable JSON file per player, outside the world:

```json
{
  "uuid": "…",
  "name": "Alice",
  "skins": {
    "candy_sword": { "claimed": "2026-10-24T10:00:00Z", "application": { "id": "…", "world": "…", "kind": "sword" } },
    "crown": { "claimed": "2026-10-24T10:05:00Z", "application": null }
  }
}
```

- It's the only record of who owns what. The name is the last one seen, for display only; identity is the
  UUID.
- **Saved at once** on every Claim, apply, remove, grant and revoke. A Claim saves first and only then
  uses up the token, so a failed save keeps the token.
- **The running server wins.** A file is read the first time its player is needed and kept in memory
  after that, and every change writes it whole. Edit files only while the server is stopped.
- **Damaged files are never overwritten.** A file that doesn't parse, or has a field this mod doesn't
  know, is logged; until it's fixed that player has no skins, and their Claims and applies are refused.
  `/skins info` says what's wrong. It's read again on every look, so a fix takes effect at once.
- Each application carries the world's stamp from `<world>/metacraft-skins.json`. In a fresh world (or
  any other world) the stamps don't match, so every skin reads Unapplied there ("Applied in another
  world" in `/skins info`) with no admin step, and Owners apply them again.

There are no merge, import or export commands, and no migration between online- and offline-mode
UUIDs.

## Moving servers

With the server **stopped**, copy two folders to the new server:

- `skins-data/` (who owns what);
- `config/metacraft-skins/skins/` (the definitions; and `config/metacraft-skins.json5` for the settings).

Copying the world as well keeps every application: skins stay on their items. A fresh world shows every
skin Unapplied, ready to apply again.

A copied player file **overwrites** the file of the same UUID on the new server; files are never merged.
If a player already has skins on the new server, merge the two files by hand first.

## How items behave

Rules that answer most support questions:

- **One item at a time.** Applying a skin to another item, removing it, revoking it, or another skin
  overwriting it makes every other item carrying it plain at once, wherever it is: chests, ender chests,
  shulker boxes, bundles, item frames, the ground, other players. The marker is cleared the next time the
  server touches the item: an online player's items within a tick, dropped and framed items in loaded
  chunks, a container when it's opened, an offline player's items when they join, anything in a chunk as
  it loads. A container nobody opens keeps a stale marker, which is harmless: it's never shown or used.
- **Duplicated copies** (a creative clone, a dupe) all carry the skin until the Owner moves or removes it;
  then only the new item does.
- **Overwritten skins never come back.** Applying over someone else's skin makes theirs Unapplied (still
  theirs). Removing the new skin later leaves the item plain.
- **What keeps a skin:** the anvil (renaming and repairing), the grindstone, enchanting and Mending. The
  smithing table's netherite upgrade keeps it only if the skin allows netherite (it has no `materials`,
  or lists `netherite`): a diamond-only skin falls off the upgraded item. So does a smithing result of
  another Kind (only possible with data packs). A skin that falls off is plain at once and goes
  Unapplied for its Owner when the server next touches the item. **Crafting-grid repair** makes a new item, so the skin is lost;
  the Owner applies it again.
- **Tooltips.** A Skinned Item shows, after its own lines, "Skin: <Skin>" (gold), the Perk lines, and
  "Owned by <player>" (the last known name of an offline Owner).
- **Tokens** stack to 64, can be traded and dropped, and are paper to recipes, villagers and workstations,
  none of which accept them. A token for a skin the viewer already owns says so and is kept when
  right-clicked. A Claim is a right-click with the token in the main hand, on air, a block or an entity;
  it never opens a chest or fills a frame. A token in the offhand does nothing.

## Development

```bash
JAVA_HOME=<a JDK 25> ./gradlew :mods:metacraft-skins:build
JAVA_TOOL_OPTIONS="-Dfabric-api.gametest=true" ./gradlew :mods:metacraft-skins:runServer -PrunDir=run/gametest
```

All the rules live in one class, `Skins`, which reads and writes only under the root it's given, so each
game test points it at a temporary folder with fixture skins. Commands (`command/`), menus (`ui/`), the
token item, the pack delivery (`pack/`, `compat/resource_packs/`) and the event listeners (`MetacraftSkins`) are thin adapters around it. The game tests
are in `gametest/`, registered under `fabric-gametest` in `fabric.mod.json`. Test skin ids end in `_test`, so they never
match a skin in the test server's own skins folder: the server's stale sweep would clear a test item
carrying one.
