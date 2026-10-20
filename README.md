# Lumen

**A keyboard for Android that glows.**

![Lumen keyboard in three themes: typing in Aurora, autocorrect in Frost, emoji in Sunset](docs/screenshots/hero.png)

Lumen is a fast, private, beautifully drawn keyboard. Every key, icon and effect
is painted in code: keys squash and spring back under your fingers, a soft bloom
of light spreads from each press, and fifteen hand-tuned themes (including
Material You and a real frosted-glass theme) make it feel at home on any phone.

## Highlights

- **Light you can feel.** Spring-loaded keys, light blooms, floating key pop-ups,
  a gradient enter key and crisp icons drawn on a single 24-unit grid.
- **Swipe the cursor.** Swipe left anywhere on the keys to jump back a word.
  Swipe right to finish the word you're typing with the middle suggestion, jump
  forward a word or, with no words ahead, add the next word Lumen suggests.
- **Autocorrect that knows the keyboard.** It weighs which keys are neighbours and
  exactly where you tapped, so a slip is fixed even when it spells another word
  (`bur` → `but`), as is a letter that never registered (`wich` → `which`, `th` →
  `the`). It reads the word before (`thank tou` → `thank you`, `I jeep` → `I keep`)
  and pulls apart words run together, whether the space was missed (`thelink`) or
  landed on a letter just above it (`sincebthe` → `since the`). It adds missing
  apostrophes (`theyre` → `they're`), leaves chat shorthand alone (`ngl`, `ppl`,
  `nah`), corrects searches typed in the browser's address bar but never web
  addresses, and one tap of backspace undoes it.
- **Voice typing.** Tap the microphone in the toolbar and talk: your words appear
  as you speak, then land in the text with the right spaces and capitals. It
  listens in British or American English to match your spelling.
- **British or American English.** Lumen spells the way your phone's region does
  (or the way you pick in settings): in British English, colour, favourite, realise
  and programme are words it suggests, not typos it fixes.
- **Predictions.** Word completions, next-word predictions, emoji suggestions
  (🍕 when you type “pizza”), smart punctuation spacing, double-space full stop and
  auto-capitalisation.
- **Search from the keyboard.** Find any emoji by name (“pizza”, “lol”), search
  GIFs and send them straight into the conversation, or look something up on the
  web with the text you've selected already filled in.
- **Learns you, privately.** New words and phrases are learned on the device and
  stay there unless you back them up, and Lumen never learns from password or
  incognito fields.
- **Comes with you.** Back up your settings and the words Lumen has learned to a
  file you choose, then restore them on a new phone. The restored words join any
  it has learned there already.
- **Fifteen themes.** Aurora, Material You, Glass (real background blur on
  Android 12+), Graphite, Midnight (AMOLED), Carbon, Slate, Frost, Paper, Sakura,
  Lilac, Ocean, Forest, Sunset and Neon, all switchable from the keyboard.
- **Make it yours.** Give any theme one of eight accent colours for the enter key,
  highlights and the glow of each press. Keys come raised, flat, outlined or
  borderless, in four shapes, with five fonts (regular, light, bold, condensed and
  mono) and three letter sizes.
- **Everything at hand.** A toolbar with search, emoji, GIFs, clipboard and voice
  up front, and themes, one-handed and settings behind its ⋯. Emoji with skin
  tones and recents, clipboard history with pins and a one-tap paste chip, swipe
  left from backspace to delete whole words, long-press for accents and symbols,
  an optional number row, and QWERTY, QWERTZ, AZERTY, Colemak and Dvorak layouts.
- **Symbols with a flick.** Flick down on a key for the number or symbol in its
  corner (1 on Q, @ on A), no long press or symbols page needed.
- **Made for foldables.** Open a foldable (or use a tablet) and the keys split
  between your thumbs: five columns a side with the opening over the hinge, G and V
  on both halves, and one long space bar across the middle.
  Fold or unfold mid-word and you carry on with the same word, suggestions, page
  and panel. Number pads stay compact on wide screens, and the app becomes a
  centred column that keeps its place.
- **One-handed or floating.** On a phone, the one-handed button (behind the
  toolbar's ⋯) moves the keys over to your thumb, with buttons beside them to swap
  sides or go back to the full width. On a tablet or an open foldable, the keyboard
  can float instead: a card you drag anywhere by its handle, while the app keeps
  the whole screen.
- **Built to be light.** No third-party libraries; the keyboard is a handful of
  custom views drawn on the canvas.

## Screenshots

![Every Lumen theme](docs/screenshots/themes.png)

![Cursor swipes, accents, key pop-ups, paste chip, clipboard and theme panels](docs/screenshots/features.png)

![Searching emoji by name, and searching GIFs, from the keyboard](docs/screenshots/search.png)

![Typing on a folded phone, then unfolding mid-word: the same word carries on with the keys split either side of the hinge](docs/screenshots/foldable.png)

![The Lumen app in light and dark mode](docs/screenshots/settings.png)

## Install

1. Download `lumen-<version>.apk` from the
   [latest release](../../releases/latest) and install it.
2. Open **Lumen** and follow the two setup steps: turn Lumen on, then switch to it.

### Updates

The Lumen app keeps itself current. Once a day it looks for a new version and,
when one is out, sends a notification; tap it (or **Update** on the app's
**Updates** card) to install it over the old one, keeping your settings and
learned words. Android asks once to let Lumen install apps, and then confirms
each update. Turn off **Check automatically** to check only when you tap **Check**.

### Releases (for the repository owner)

Every merge to `main` is built, signed with Lumen's own key and published as a
release, which is what the app updates from. The key lives in two repository
secrets, `LUMEN_SIGNING_KEY` and `LUMEN_SIGNING_PASSWORD`, never in the code:
whoever holds it can ship updates to your keyboard.

To create it once, without anyone seeing it:

1. [Make a fine-grained token](https://github.com/settings/personal-access-tokens/new)
   for this repository only, with **Repository permissions → Secrets: Read and
   write** and a short expiry.
2. Add it as a repository secret named `LUMEN_SETUP_TOKEN`
   (**Settings → Secrets and variables → Actions → New repository secret**).
3. Run the **Create signing key** workflow from the **Actions** tab. It makes
   the key straight into the two secrets and deletes `LUMEN_SETUP_TOKEN`; you
   can then delete the token itself.

To bring your own key instead, put a base64 PKCS12 keystore holding a key named
`lumen` in `LUMEN_SIGNING_KEY` and its password in `LUMEN_SIGNING_PASSWORD`.

Secrets can't be read back, so no copy of the key exists anywhere else; deleting
it means installing the next version fresh. Without the secrets, CI signs with a
throwaway debug key and publishes no release; those APKs (the `lumen-apk`
artifact of each **Build** run) only install after uninstalling Lumen.

## GIFs

GIF search uses [GIPHY](https://developers.giphy.com/) or
[KLIPY](https://partner.klipy.com/), and both need a free API key. (Tenor's API,
which many keyboards used, was shut down in June 2026.)

1. Get a key. For GIPHY, sign in to the developer dashboard, choose
   **Create an App** and pick **API**. For KLIPY, sign up to the partner panel and
   add a key under **API Keys**.
2. In the Lumen app, under **GIFs**, choose the service and paste the key.

To build a key into the app instead, add a repository secret named
`GIPHY_API_KEY` or `KLIPY_API_KEY` (CI builds pick it up), or pass
`-PgiphyApiKey=...` or `-PklipyApiKey=...` to Gradle. Anyone with the APK can read
a built-in key, so keep those builds to yourself. Free keys allow about 100
requests an hour; Lumen reuses pages it loaded in the last ten minutes, so
reopening the GIF panel costs nothing.

## Build

You need JDK 17+ and the Android SDK (Android Studio is the easiest way).

```sh
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # typing engine tests
```

## How it works

| Package | What lives there |
| --- | --- |
| `engine/` | Pure-Kotlin typing engine: a compact trie of 160,000 words (in American or British spelling), a weighted Damerau–Levenshtein search driven by a tap spatial model, a word-pair model for context, next-word prediction, on-device learning and emoji search. Unit-tested on the JVM. |
| `keyboard/` | Layouts, the key painter, the icon set, `KeyboardView` (touch handling, rollover, long-press panels, flicks, cursor and delete swipes, animations). |
| `ime/` | `LumenIME` (the input method service), `InputLogic` (composing, autocorrect with undo, smart spacing, cursor tracking), haptics and the engine loader. |
| `update/` | The in-app updater: finds the latest GitHub release, downloads it and hands it to Android's package installer. |
| `ui/` | The suggestion strip, the search bar, the emoji, GIF, clipboard and theme panels, and the root view that handles window insets and the one-handed and floating keyboards. |
| `data/` | Emoji data, clipboard history, and GIFs: the GIPHY/KLIPY client, preview loading and the content provider that hands GIFs to apps. |
| `theme/` | Theme definitions, including Material You colours from the wallpaper. |
| `settings/` | The companion app: setup, live theme gallery, preferences, and backups. |

The dictionary, word-pair and emoji assets are generated from upstream data by the
scripts in `tools/`; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Privacy

The keyboard only goes online for GIFs. Opening the GIF panel loads trending
GIFs from the service you chose (GIPHY or KLIPY), and your GIF searches go there
too, with your language and region; like any website, the service also sees your
IP address. Nothing else you type is sent anywhere. The Lumen app asks GitHub
whether a new version is out when you open it and once a day, and downloads
updates from there.
With GIF search and automatic update checks turned off, Lumen never connects on
its own. Voice typing hands what you say to your phone's speech service (usually
Google's), which may send it online; Lumen keeps no recordings. Web search hands your search to your browser or search app. Learned
words stay in the app's private storage, are left out of Android's backups and
transfers to a new phone, and can be cleared from the app. The app's Backup
section saves them, with your settings, only to a file you choose; GIF service
keys and clipboard text are never included. Clipboard history is kept in
memory for an hour (pinned clips are saved), and clips that apps mark as
sensitive, such as passwords, are never recorded.
