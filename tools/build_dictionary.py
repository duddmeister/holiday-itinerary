#!/usr/bin/env python3
"""Builds Lumen's English dictionary assets from an AOSP LatinIME word list.

Input:  an AOSP ".combined" word list (optionally gzipped), e.g.
        dictionaries/en_US_wordlist.combined.gz from
        https://android.googlesource.com/platform/packages/inputmethods/LatinIME
        (Apache License 2.0).

Output: app/src/main/assets/dict/en_US.txt
            One entry per line: word<TAB>frequency[<TAB>flags]
            Sorted by UTF-16 code unit order so the app can build its trie in a
            single streaming pass. Frequency is AOSP's 0..255 log scale.
            Flags: "o" = possibly offensive (never offered as a suggestion).
        app/src/main/assets/dict/en_US_shortcuts.txt
            input<TAB>replacement<TAB>mode, taken from AOSP's shortcut entries.
            mode "a": the input is not a word, so it is auto-corrected.
            mode "s": the input is a real word, so the replacement is only
                      offered as a suggestion (e.g. "ill" -> "I'll").

Usage:  python3 tools/build_dictionary.py path/to/en_US_wordlist.combined.gz
"""
import gzip
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Real but rare words whose shortcut is what people nearly always mean.
ALWAYS_CORRECT = {"cant", "wont"}

# Everyday words newer than the AOSP list (chat, apps, brands), with frequencies
# on the same 0..255 scale. They are only added when missing.
EXTRA_WORDS = {
    "lol": 150, "omg": 130, "ok": 170, "btw": 110, "idk": 120, "tbh": 100, "imo": 90,
    "imho": 70, "brb": 90, "ttyl": 80, "lmao": 110, "lmfao": 70, "rofl": 60, "hahaha": 110,
    "hehe": 100, "thx": 100, "plz": 80, "u": 120, "ur": 100, "bc": 80, "cuz": 90, "nvm": 80,
    "jk": 90, "smh": 80, "fyi": 90, "irl": 70, "dm": 90, "dms": 70, "selfie": 110,
    "selfies": 90, "emojis": 100, "hashtag": 100, "hashtags": 80, "wifi": 120,
    "smartphones": 90, "TikTok": 110, "Uber": 110, "Lyft": 80, "Zoom": 120, "Venmo": 90,
    "Gmail": 100, "Reddit": 100, "Discord": 100, "Slack": 100, "Airbnb": 100,
    "ChatGPT": 100, "COVID": 120, "vax": 70, "livestream": 90, "unfollow": 80, "vlog": 80,
    "memes": 110, "gif": 100, "GIF": 90, "FaceTime": 100, "Siri": 90, "Bitcoin": 100,
    "crypto": 100, "NFT": 80, "bestie": 90, "besties": 70, "sus": 80, "woohoo": 90,
    "awww": 90, "aww": 100, "meh": 90, "mhm": 80, "hm": 90, "omw": 90, "tmrw": 80,
    "tmr": 60, "bday": 90, "gf": 80, "bff": 80, "hbd": 70, "lmk": 100, "np": 90, "ty": 100,
    "tysm": 80, "ily": 80, "wyd": 80, "hbu": 70, "wbu": 70, "idc": 80, "ikr": 80, "rn": 100,
    "ofc": 90, "vid": 90, "vids": 80,
}
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "dict")


def parse_attrs(line):
    attrs = {}
    for part in line.strip().split(","):
        if "=" in part:
            key, value = part.split("=", 1)
            attrs[key] = value
    return attrs


def utf16_key(s):
    return s.encode("utf-16-be")


def main(path):
    opener = gzip.open if path.endswith(".gz") else open
    words = {}
    shortcuts = []
    current = None
    with opener(path, "rt", encoding="utf-8") as f:
        for raw in f:
            if raw.startswith(" word="):
                attrs = parse_attrs(raw)
                word = attrs["word"]
                current = attrs
                if attrs.get("not_a_word") == "true":
                    continue
                if any(ch in word for ch in "\t\n\r"):
                    continue
                freq = max(0, min(255, int(attrs.get("f", "0"))))
                offensive = attrs.get("possibly_offensive") == "true" or "offensive" in attrs.get("flags", "")
                previous = words.get(word)
                if previous is None or previous[0] < freq:
                    words[word] = (freq, offensive)
            elif raw.startswith("  shortcut=") and current is not None:
                attrs = parse_attrs(raw)
                # AOSP also whitelists some real words ("hid" -> "his"); those stay
                # suggestions so that deliberately typed words are never replaced.
                auto = (
                    current.get("not_a_word") == "true"
                    or "nonword" in current.get("flags", "")
                    or current["word"] in ALWAYS_CORRECT
                )
                mode = "a" if auto else "s"
                shortcuts.append((current["word"], attrs["shortcut"], mode))

    for word, freq in EXTRA_WORDS.items():
        words.setdefault(word, (freq, False))

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "en_US.txt"), "w", encoding="utf-8", newline="\n") as out:
        for word in sorted(words, key=utf16_key):
            freq, offensive = words[word]
            out.write(f"{word}\t{freq}\to\n" if offensive else f"{word}\t{freq}\n")
    with open(os.path.join(OUT_DIR, "en_US_shortcuts.txt"), "w", encoding="utf-8", newline="\n") as out:
        for source, replacement, mode in sorted(set(shortcuts)):
            out.write(f"{source}\t{replacement}\t{mode}\n")
    print(f"{len(words)} words, {len(shortcuts)} shortcuts written to {OUT_DIR}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
