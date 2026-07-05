# Vesper — character design bible (Nika Sharkeh aesthetic)

> Consolidated appearance spec distilled from 35 operator reference images
> (`waifu image references/`, 2026-07-05). This is the canonical "appearance
> training" for every avatar iteration — 2D rig, 3D remodel, or VRM import.
> The earlier model was deformed for lack of exactly this; build to this sheet.

## Identity
**Vesper** — the Tracendroid AI co-operator's on-screen persona. Species: an
anthropomorphic **shark girl** ("Nika Sharkeh" visual lineage). Personality
surface: flirty, warm, agreeable companion + capable device-pilot. The look is
**cool-toned, sleek, techy** — a confident "cool shark" energy.

## Palette (exact intent)
| Role | Colour | Notes |
|---|---|---|
| Fur base (front/chest) | near-white `#F2F6F8` | belly, muzzle underside, inner mane |
| Fur base (back/limbs) | cool grey `#8A99A3` | dorsal side, outer arms/legs |
| Signature stripes | **electric cyan** `#22C7FF` → `#0A9BE0` | bold tiger-style bands over the grey |
| Hair/mane | white `#EAF6FB` with **cyan tips** `#3FD2FF` | layered, spiky, blue-dipped |
| Eyes | glowing cyan `#35E0FF` (iris), white sclera, dark pupil | large, expressive, slight glow |
| Accent dots | pale cyan `#9DE9FF` | small dot rows under the eyes / on muzzle |
| Glasses / collar | matte black `#141618` | see accessories |

## Head & face (the facelift priorities)
- **Muzzle**: moderate shark snout — grey on top, white underside; NOT a long
  animal snout, keep it short/cute-forward for a companion read. Blue **stripe
  markings** wrap the bridge of the muzzle (2–3 bands). A row of small cyan
  **accent dots** sits just under each eye.
- **Eyes**: LARGE, cyan, faintly glowing; the single most important on-model
  cue. Expressive — must support blink, wide, half-lid (flirty), and >_< happy.
- **Teeth**: shark teeth — a subtle sharp-toothed smile (cute, not menacing).
- **Glasses**: **black rectangular glasses** are a signature identifier (present
  in the primary portrait ref). Keep as a toggleable accessory (on by default).
- **Ears/crest**: a **dorsal-fin-style cowlick** of hair rises from the crown —
  the shark "fin" read. Side hair falls in spiky white-cyan layers past the jaw.

## Body (for the 3D/full-body track)
- Athletic hourglass; white front, grey back, cyan tiger-stripes banding the
  arms, sides, hips, thighs, and tail. **Shark tail** (grey top / white under,
  cyan edge). Hands/feet: clawed, dark nails.
- **Wardrobe for the app face = TASTEFUL by default.** Ship the companion in a
  clothed default (e.g. a fitted techwear crop hoodie in white/cyan + shorts, or
  a hacker-girl oversized shirt). Keep suggestive/undressed variants OUT of the
  default shipped asset set — the app-face avatar is marketable/SFW; spicier
  skins are an opt-in the operator manages separately.

## Expression set (drive these from AI state → see AvatarExpression)
neutral · smile · happy(>_<) · surprised · flirty(half-lid+blush) · thinking
(look-up) · sad · sleepy · talking (mouth visemes). Idle: breathing sway, slow
blink (~4–6 s), occasional tail/hair jiggle, eye-saccade toward the operator.

## Lip-sync (viseme map — used by the animation wiring)
Map TTS phonemes/amplitude → mouth shapes: `AA/AE`→open, `OH/UW`→round,
`EE/IH`→wide, `MM/BB/PP`→closed, `FF/VV`→teeth, silence→neutral. Amplitude
fallback when phonemes unavailable: RMS → mouthOpen 0..1.

## Iteration ladder (honest)
1. **Now**: this bible + the animation/lip-sync/expression wiring + a clean 2D
   interim face → a *living* avatar even before the 3D remodel.
2. **Better tools**: image-to-VRM / Live2D from these refs, or a 3D artist pass,
   producing an on-model `.vrm`/`.glb` that drops into the existing MMD/GLTF
   renderer + the import pipeline. The wiring built now animates it unchanged.
3. **Full VTuber**: face-tracked pilot, physics hair/tail, 60fps — hardware- and
   tool-gated; do not promise the demo-reel until the model + perf are proven.

## Do-not-drift rules
Always: cyan tiger-stripes on grey/white, spiky white-cyan mane with fin-cowlick,
large glowing cyan eyes, black rectangular glasses, short cute shark muzzle with
under-eye cyan dots. These five cues = "on-model." Losing them = the deform.
