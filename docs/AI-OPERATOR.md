# The AI operator — an LLM that drives the phone

> "the ai operator that can control the phone — to give masamune an n8n type
> flow plane" — user, 2026-08-02

## The one decision everything else follows from

**The operator is a fiber.** It is not a subsystem bolted beside the flow plane;
it drives the *same* graph runtime (`docs/donors/RE-automate.md`). Every action
it takes is a block on a canvas that can be watched, logged, single-stepped and
stopped.

The alternative — an agent loop that reaches straight into the Accessibility
service and acts — was rejected on sight. An operator that acts outside the graph
is an operator the user cannot audit, and "an LLM is silently tapping around your
banking app" is the single worst version of this feature. Routing it through the
fiber runtime means the operator inherits, for free, everything the runtime
already guarantees: a visible current block, a serializable halt point, a
step/pause/stop control, and a persisted trace.

This also answers "n8n type flow plane" precisely. n8n's pitch is that an agent
and a hand-built workflow are the same substrate. Here they literally are: the
operator emits and walks a `FlowGraph`, exactly what the palette user builds by
hand.

## Harvest, don't reinvent — Operit is the donor

The legacy `:app` (Operit) already carries a complete AccessibilityService
automation stack, and Operit is a named donor. Its organs, at
`app/.../core/tools/defaultTool/accessbility/AccessibilityUITools.kt` (783 lines,
already in-tree):

| Operit operation | What it gives the operator |
|---|---|
| `getPageInfo` | the current screen's accessibility hierarchy |
| **`simplifyLayout`** | **raw a11y XML → a compact node tree an LLM can actually read.** The highest-value organ — a full hierarchy blows the context window; the simplified tree is what makes the observe step affordable |
| `clickElement` | tap a node by text / id / bounds |
| `setInputText` | type into a focused field |
| `tap` / `longPress` / `swipe` | gesture dispatch via `dispatchGesture` |
| `pressKey` | hardware/IME key events |
| `captureScreenshot*` | pixels, for a vision model or for the user's audit trail |
| `UIHierarchyManager`, `AccessibilityActionListener`, `IAccessibilityProvider.aidl` | the plumbing under all of the above |

The rule from the campaign holds: **port the donor faithfully first,
differentiate after.** These are read as the reference for how the operator sees
and touches the screen, not reimplemented from a description of what an
accessibility service does — that reimplement-from-memory move is the exact
thing that produced the earlier broken rounds.

## One action vocabulary, shared with the manual flow plane

Automate's `Interface` category already defines the manual-flow versions of every
one of these: `Inspect layout`, `Interact`, `Interact touch`, `Inspect text
edit`, `Key send`, `Key send characters`. The operator does **not** get a private
action set — it emits those same blocks. So:

- a flow the operator wrote can be opened, read and edited by the user;
- a flow the user built by hand can be handed to the operator to continue;
- there is one gating story, one serializer, one trace format, not two.

The operator's "tools", in LLM-function-call terms, are a thin projection of the
`Interface`-category `BlockSpec`s. The model chooses a block and its arguments;
the runtime places and runs it. The model never calls the AccessibilityService.

## The loop

Observe → decide → act, each step a block, the whole loop a fiber:

1. **Observe** — an `Inspect layout` block runs `simplifyLayout(getPageInfo())`
   and binds the compact tree (and optionally a screenshot) to a variable.
2. **Decide** — a block hands the observation + the user's goal to `AiService`
   (the existing provider layer) and gets back the next action as a block choice
   with arguments. This is a `Decision`/`Action` pair in the graph, so the
   model's reasoning is a visible node, not a hidden step.
3. **Act** — the chosen `Interact` / `Key send` / `App start` block runs.
4. Loop until the goal-check `Decision` says done, an error routes to a
   `Failure catch`, or the user halts.

Because a fiber is serializable at every block boundary, the operator survives
process death mid-task and resumes at its last block — the persist-and-resume
property is not a bonus here, it is what makes a long operator task trustworthy.

## Gating and control — stricter than the rest of the suite

The operator can touch anything on screen, so its guards are the tightest in the
suite and they are non-negotiable:

- **AccessibilityService gate.** Every operator block carries
  `Requirement.Accessibility`. Service off ⇒ the operator surface is disabled
  with the sentence naming what is missing, exactly as the palette gates the
  cross-app interaction blocks (`Interact`, `Inspect layout`, `Key send` — not
  the `Interface *` own-UI family, which needs no grant). No operator action is
  even placeable without it.
- **Capability gate.** Actions route through the existing
  `core/capability/CapabilityGate` with `Caller.AiAgent` as the caller. The gate
  already distinguishes an AI caller from a user tap and persists per-(caller ×
  capability) grants; the operator does not get a blanket pass.
- **Halt.** The existing `core/halt/HaltController` is the operator's stop
  button. Because the operator is a fiber, halt is the runtime's own
  stop-fiber-at-block-boundary — it is not a best-effort flag the agent loop
  chooses to check. The user can stop it between any two actions and it stops.
- **Every action is surfaced.** The `FiberMonitor` view shows the operator's
  current block live. "Surface every action it takes" is not extra work — it is
  what the flow runtime already does for every fiber.

## Which "app by llama inc"

The user wrote "automate by llama inc((???))" — the parenthetical is the app's
own uncertainty, and it is worth resolving in writing so nobody re-guesses. The
donor for the *flow plane* is **Automate by LlamaLab** (`com.llamalab.automate`),
documented in `docs/donors/RE-automate.md`. It is not related to Meta's Llama
models, nor to any "Llama Inc." The operator's *LLM* is a separate concern served
by Masamune's existing `AiService` provider layer, and can be any provider the
user has signed into via the OAuth redirect flow (`docs/AUTH-SUBSCRIPTION.md`).

## Honesty ledger

| Claim | Status |
|---|---|
| Operit's a11y stack provides the operator's action primitives | **In-tree**, read as donor reference |
| Operator actions share the `Interface` block vocabulary | Design fixed here; blocks are built by the catalog work package |
| Operator = fiber, so halt/trace/resume are inherited | Depends on the flow runtime (`flow/runtime/`), **not yet built** |
| The observe→decide→act loop works end to end | **Not built or device-tested.** Ships gated until it is |
| Any operator action with the a11y service off | Reports absent; never silently no-ops |
