# Design

KhataGo's visual language is one decision repeated everywhere: **a ledger should look like a well-kept
account, not a game.** Calm surfaces, one accent family, colour reserved for meaning, and numbers that are
the loudest thing on the screen because numbers are what the user came to read.

There is no design tool in this repository and no Figma link that only one person can open: the tokens below
*are* the spec, and they are the only place a hex value is allowed in the UI layer.

---

## Tokens (`ui/theme/`)

### Colour — `KhataGoColors`

| Token | Value | Use |
|---|---|---|
| `Emerald900 … Emerald50` | `#06382A → #E9FAF1` | brand ramp: text, hero surfaces, tints. Never a gradient for decoration |
| `Ink900 … Ink200` | `#0E1C17 → #DCE4E0` | warm-tinted slate for text and hairlines — soft, not clinical |
| `SurfaceWhite` / `SurfaceCanvas` `#F7FBF9` / `SurfaceTinted` `#F1FAF5` | | cards, screen background, section fills |
| `Settled` `#0F8A5F` on `SettledBg` `#E1F6EC` | | paid, cleared, "nothing outstanding" |
| `DueSoon` `#A5670A` on `DueSoonBg` `#FDF1DC` | | due today/this week |
| `Overdue` `#B3261E` on `OverdueBg` `#FCE9E7` | | past due — the only red in the app |
| `OwedToMe` `#0E6F9C` on `OwedToMeBg` `#E2F2FA` | | money owed **to** the user, deliberately a different hue from "settled" |
| `Info` `#4A5A67` on `InfoBg` | | neutral notes, cancelled, system copy |
| `ChartRamp` | 8 distinguishable hues | charts; always paired with a text label, never colour alone |
| `HeroGradient` / `CardGradient` | emerald-only | the two gradient surfaces, both in `KhataGoColors` |

**Rule: colour marks meaning, never decoration.** Two directions of debt must never share a hue, which is why
"I owe" is emerald/ink and "I am owed" is blue rather than green/red — red is reserved for "you are late", and
using it for money coming in would train the user to feel alarm about the good number.

Light-only is a product requirement, not a preference: `KhataGoTheme(forceLight = …)` takes the parameter and
ignores it so a caller cannot request dark, `values-night` pins the theme, `android:forceDarkAllowed=false`, and
`AppCompatDelegate.MODE_NIGHT_NO` is set in `KhataGoApplication` so even framework dialogs and the status bar
stay light when the device is dark.

### Type — `KhataGoTypography`

| Token | Size / weight | Use |
|---|---|---|
| `displayLarge` | 38 sp / Bold, −0.6 sp tracking | one per screen at most (the hero figure) |
| `headlineMoney` | 30 sp / Bold, −0.5 sp | the primary number on a card |
| `sectionMoney` | 20 sp / SemiBold, −0.2 sp | per-module outstanding figures |
| `figure` | 15 sp / SemiBold | list-row amounts |
| `build` (a `Typography` getter) | 30/23/20/18/16/14/12.5 sp scale | the Material 3 slots, anchored on the same weights and tracking |

Money figures use the large tokens with negative tracking, which is what keeps 30 sp digits from looking like
a price tag. `AnimatedMoney` is the only animation applied to a number, it animates the *change* rather than
revealing the value, and it queries `LocalAccessibilityManager.shouldReduceMotion()` to render the final
figure immediately when the user has asked for reduced motion.

### Spacing & shape

`KhataGoSpacing`: `xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 20 · xxl 28 · hero 36 · screen 20`.
The 20 dp gutter is what keeps cards off the display curve on a small phone while still looking deliberate on
a tablet; `hero` is only used by the home dashboard block.

`KhataGoRadii`: `card 24 · innerCard 18 · field 16 · chip 12 · button 16 · sheet 30`. There is no `pill`
radius and no `0` radius in the UI: a ledger that is all sharp corners reads as a form, and all-round radii
read as a toy. Nested surfaces step 24 → 18 so containment is legible without a shadow.

Shadows are not the depth mechanism — tinted fills and 1 dp hairlines are, because a raised card on a white
background is invisible in bright sun, and this app is used at a counter.

## The component kit (`ui/components/`)

Screens are assembled from these and do not reinvent them:

| Component | Notes |
|---|---|
| `KhataGoCard`, `SectionHeader`, `InfoTile` | surfaces and section rhythm |
| `RecordRow`, `StatusPill`, `PayoffBar`, `MoneyFigure`, `AnimatedMoney` | the row vocabulary shared by every module list |
| `PrimaryButton`, `TonalButton`, `TextActionButton`, `FilterChipRow` | actions; destructive actions are never a `PrimaryButton` |
| `EmptyState`, `FadeInContent` | the "no records yet" and first-frame states |
| `Label`, `TextFieldLine`, `AmountField`, `DateField`, `NoteField`, `FormSection`, `SwitchRow`, `ChoiceTile`, `FormScaffold` | forms; `AmountField` is the only door to `MoneyParseResult` |
| `DetailTopBar`, `AttachmentViewer` | record-level chrome |
| `LineChart`, `BarChart`, `DonutChart` | analytics; each takes explicit `windowLabel` and `maxValue`, and states them in the UI |
| `moneyText`, `statusLabel`, `dueDateText`, `toneForLedger`, `toneForDue`, `humanBytes`, `payableFromKey`, `PayableType.shortLabel` | the shared derivation helpers — a screen that formats an amount or a status by hand is a bug |
| `KhataGoIcons` | the **only** file allowed to reference `Icons.*`; it exposes a named set, so an icon rename is a one-line change |
| `rememberContainer`, `khataGoViewModel`, `rememberMoneyFormatter` | composition entry points (`ViewModelAccess.kt`) |

## Screen-level rules

- **Numbers before labels.** Every card leads with the figure; the caption under it is 12–13 sp and optional.
  A user scanning for "how much do I owe" should not have to read a sentence.
- **Two directions, two places.** "I owe" and "I am owed" never share a card, a row, or a sign.
- **Remaining is shown, never recomputed by the screen** — it comes from `ObligationSnapshot` /
  `ScheduleProgress` ([MONEY.md](MONEY.md#5-derived-balances--the-rule-the-whole-app-hangs-on)).
- **Destructive and irreversible actions** get plain language, a count of what is affected, and (for a wipe)
  three taps. No euphemisms, no "Are you sure?".
- **Errors explain the permitted value.** A rejected overpayment says "The largest payment allowed is
  ৳4,000.00", not "Invalid input".
- **Charts state their window and their maximum.** A chart without a date range is a mood, not data.
- **No skeletons that lie.** The first frame is a zeroed snapshot labelled as such; a spinner over a number
  the user needs *now* is worse than an honest zero that updates in place.
- **Deep-link and share targets are destinations, not dialogs** — Quick Add is a screen for exactly this
  reason (a dialog cannot be reached from `khatago://add`, and a dialog cannot be backed out of safely).

## Motion

Fade and a 120–200 ms settle, nothing more. `AnimatedVisibility` + `fadeIn/fadeOut` on the splash handover,
`AnimatedMoney` on a changed figure, no shared-element morphs and no parallax: an app people use one-handed at
a counter must not compete with itself for the thumb. Haptics are used only on success of a money write, which
is the one moment a confirmation is genuinely useful.

## Icons, launcher and brand marks

- `assets_src/brand/flat_square.png` (square master) and `logo_wordmark.png` are the sources;
  `regenerate_icons.sh` derives every `mipmap-*` density, the `drawable-nodpi` copies, the monochrome
  adaptive layer, `ic_stat_khatago` (notification) and the splash icon. Generated files are committed so a
  build needs no image toolchain, and must never be hand-edited.
- The checked-in artwork is an original placeholder produced for this repository, so the build is
  self-contained. Swapping in official art is: replace the master, re-run the script, commit the diff.
- The notification icon is a monochrome vector on a transparent field — anything else becomes a grey square on
  some OEM skins.
- Icons come from `Icons.Outlined.*` (with `Icons.AutoMirrored.Filled.ArrowBack` where the platform requires
  it), always via `KhataGoIcons`: outlined because filled glyphs at 20 dp in a dense row read as blobs.

## Accessibility

- Body text at 15 sp+ on white; `Ink500` on `SurfaceWhite` clears 4.5:1, and the meaning colours are chosen
  against their own tinted backgrounds, not against white.
- Never colour alone: every status colour is paired with a word (`StatusPill`, `statusLabel`) and every chart
  series with a label.
- A `RecordRow`'s clickable region is the whole card (12 dp vertical padding around ~21 dp of content), and a
  primary action is a full-width `Button` — both comfortably in the 48 dp range. `AmountField`/`DateField`
  label their inputs so an announcement says what each is for, and the bottom bar keeps its four targets within
  thumb reach.
- Where a control is genuinely smaller than 48 dp (a chip in `FilterChipRow`, a `TextActionButton`), it is
  non-essential — there is always a larger path to the same action.
- Amounts read as one string (`৳1,234.50`) rather than symbol + number + unit, so a screen reader says a
  plausible amount instead of "₹ 1,234 . 5 0".
- `contentDescription` is null for decorative glyphs and explicit for the three icons that carry meaning alone
  (overdue flag, attachment, lock state).
