# design

the design language for ugly os. read this before touching any ui, in any app.
it is direction, not description — when a screen disagrees with this doc, the
screen is wrong.

## the one-liner

**nothing os, wearing nord, speaking in lowercase.**

- **nothing os** gives us the bones: dot-matrix, monospace, exposed grid,
  ruthless restraint, one thing loud per screen.
- **nord** gives us the skin: a calm arctic palette, muted by default, with
  aurora colors that only show up when they *mean* something.
- **lowercase** gives us the attitude: terse, honest, unbranded. a tool a person
  made for themselves, not a product. "scuffed" lives in the *words and tone*,
  never in broken alignment or sloppy pixels.

## principles

1. **one loud thing per screen.** pick the single element that matters — the
   clock, the search field, the top hit — and let it dominate. everything else
   recedes to muted grey. never two things shouting.
2. **the grid is visible.** align to it and don't hide it. hairline dividers,
   dotted separators, and honest edges are features. structure on display.
3. **restraint is the default.** grayscale/neutral first. color is earned, not
   decorative. an all-grey screen is a correct screen.
4. **honest, lowercase words.** labels are plain and terse. no marketing, no
   title case, no exclamation. `settings`, not `Settings`. `no tasks`, not
   `You're all caught up! 🎉`.
5. **precise, not rough.** "ugly" is a tone, not a lack of craft. baselines
   line up, spacing is on the scale, dots are round. we are scuffed in voice and
   pristine in execution.
6. **built for a person, not a store.** it can assume you know what things are.
   fewer affordances, less hand-holding, more density where a power user wants it.

## color

source of truth: `common/theme/Nord.kt` + `ThemeColors.kt`. use semantic roles
(`UglyTheme.colors.foreground`), never raw hexes in ui code.

### the neutral base — this is ~95% of every screen

| role | nord | hex | use for |
|------|------|-----|---------|
| `background` | nord0 | `#2E3440` | the canvas behind everything |
| `surface` | nord1 | `#3B4252` | cards, sheets, the search field |
| `surfaceElevated` | nord2 | `#434C5E` | a card on a card, pressed states |
| `foreground` | nord6 | `#ECEFF4` | primary text, the lit dots |
| `mutedForeground` | nord4 | `#D8DEE9` | labels, captions, secondary text |
| `subtle` | nord3 | `#4C566A` | hairlines, borders, dividers, disabled |
| `accent` | nord8 | `#88C0D0` | the *one* highlight per screen |

paint a screen in these first. if it reads well entirely in greys with a single
`accent` touch, you're done — don't add more.

### aurora — color that carries meaning

the aurora palette is **semantic, never decorative**. a color appears only when
it encodes information the user needs: a priority, a state, a category. if you
can't say what a color *means* in one word, it shouldn't be there.

| role | nord | hex | means |
|------|------|-----|-------|
| `error` | nord11 | `#BF616A` | destructive, overdue, high priority `(A)` |
| `warning` | nord13 | `#EBCB8B` | due soon, needs attention, priority `(B)` |
| `success` | nord14 | `#A3BE8C` | done, healthy, confirmed |
| `accentMuted` | nord9 | `#81A1C1` | a secondary/quieter accent |
| — | nord15 | `#B48EAD` | a spare category hue (projects/contexts) |

rules of thumb:
- accent (`#88C0D0`) marks *the interactive/primary thing* — the top hit, the
  focused field, today.
- aurora marks *state on content* — a task's priority, a due date, completion.
- never use aurora just to make a screen livelier. grey is not a problem to fix.

## type

there is exactly one font: **monospace** (`FontFamily.Monospace`). hierarchy
comes from size, weight, case, and tracking — never from a second typeface.

| role | size | weight | case | tracking | example |
|------|------|--------|------|----------|---------|
| hero / clock | huge, canvas-drawn | — | — | — | the dot-matrix time |
| page title | 28sp | bold | **lower** | 2sp | `settings` |
| body / item | 15–16sp | medium | lower / as-is | 0–1sp | a task, a result |
| input | 18sp | normal | lower | 0 | the search field |
| micro-label | 12–13sp | bold | **UPPER** | 2–3sp | `CALENDAR`, `APPS` |
| caption / hint | 13–14sp | normal | lower | 0 | dimmed empty states |

the casing rule is the heart of the voice:

- **lowercase** for anything the user reads as language — titles, items,
  placeholders, hints. `search`, `monkey dir`, `no tasks yet`.
- **UPPERCASE + wide tracking** for structural micro-labels that act as
  signposts, not sentences — section headers, the calendar month, weekday
  initials. these are the "engineered" nothing-os touch. use them sparingly;
  they lose power if everything shouts.
- **leave user data alone.** app names, contact names, and task text keep their
  original casing. we style *our* chrome, not *their* content.

no emoji in our own strings. no title case, ever.

## space & shape

spacing lives on a **4dp grid**. reach for these before inventing a value:

`4 · 8 · 12 · 16 · 20 · 24 · 28 · 48`

established rhythms (keep them consistent across screens):

- page edge padding: **20dp** horizontal.
- pages begin **48dp** from the top.
- list item gaps: **4dp** (tight rows) to **12dp** (cards).
- section spacing: **20–28dp**.

corners are soft but not bubbly. one radius per element size:

- **28dp** — big feature cards (the calendar card).
- **20dp** — standard cards, the search field, shortcut tiles, setting groups.
- **12dp** — small inline chips / the highlighted result.
- **full circle** — dots, today's date, avatars, status pips.

**dots are the motif.** the page indicator, the calendar bullet, today's marker,
and the clock itself are all dots. lean into it: prefer a small filled circle
(8dp) over an icon or a line whenever you need to mark, bullet, or indicate
something. it ties every screen back to the clock.

borders are hairlines: **1dp** in `subtle`. use them to show structure (a
non-today day cell, a divider) rather than to decorate.

## motion

quiet and mechanical, like the hardware it's pretending to be.

- transitions are short and functional; nothing bounces or flourishes.
- the horizontal pager is the primary navigation — respect it, don't fight it
  with competing swipe gestures.
- state changes (a task completing, the clock ticking) should feel like a
  segment flipping, not a celebration. no confetti, no springy overshoot.

## reach

this is a phone, held in one hand. the top of a tall screen is a two-handed
stretch; the bottom is where the thumb already rests. so **the things you touch
live low, and the things you read live high.**

- **actions gravitate to the bottom.** the primary control — `add task`, a
  compose button, a confirm — sits pinned near the bottom edge, in the thumb
  arc, not floating at the top. if a screen has one action, it belongs down there.
- **the top is for reading, not reaching.** titles, the clock, section labels
  and status are fine up high — you look at them, you don't tap them. don't strand
  a frequently-tapped control at the top just to balance the layout.
- **pinned, not scrolled away.** a bottom action stays fixed while the list
  between it and the title scrolls, split from the list by a hairline so it reads
  as a footer, not the last row. give it clearance from the page indicator and
  the system nav (a bottom inset / ~40dp).
- **sheets rise from the bottom** for the same reason — the editor, the drawer,
  any modal enters from the edge the thumb owns. (`ModalBottomSheet`, the app
  drawer's swipe-up.)

reading flows top-to-bottom; reach flows bottom-up. a good screen honors both.

## the taste test

before you ship a screen, check:

- [ ] is there exactly **one** loud element, and is everything else muted?
- [ ] does every bit of color *mean* something you can name in a word?
- [ ] is our chrome lowercase, and are UPPERCASE labels reserved for signposts?
- [ ] is everything on the 4dp grid, with baselines and dots that actually line
      up? (scuffed is a voice, not an excuse.)
- [ ] is the thing you tap most within thumb reach at the bottom, with reading
      up top? (reach flows bottom-up.)

if they all pass, it's ugly in the right way.
