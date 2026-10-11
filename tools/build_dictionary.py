#!/usr/bin/env python3
"""Builds Lumen's English dictionary assets from AOSP LatinIME word lists.

Input:  AOSP ".combined" word lists (optionally gzipped):
        dictionaries/en_US_wordlist.combined.gz and en_GB_wordlist.combined.gz
        from https://android.googlesource.com/platform/packages/inputmethods/LatinIME
        (Apache License 2.0).

Output: app/src/main/assets/dict/en_US.txt and en_GB.txt
            One entry per line: word<TAB>frequency[<TAB>flags]
            Sorted by UTF-16 code unit order so the app can build its trie in a
            single streaming pass. Frequency is AOSP's 0..255 log scale.
            Flags: "o" = possibly offensive (never offered as a suggestion).
        app/src/main/assets/dict/en_US_shortcuts.txt and en_GB_shortcuts.txt
            input<TAB>replacement<TAB>mode, taken from AOSP's shortcut entries.
            mode "a": the input is not a word, so it is auto-corrected.
            mode "s": the input is a real word, so the replacement is only
                      offered as a suggestion (e.g. "ill" -> "I'll").
        app/src/main/assets/dict/en_GB_predictions.txt
            The hand-written en_US_predictions.txt in British spelling.

English (UK) is the US list in British spelling rather than AOSP's en_GB list
as it stands: that has the British spellings, but it also leaves out everyday
words ("connections", "rigorous", "grams"). So the en_GB list adds the words the
US list lacks, and where one of those is the British spelling of a US word, the
American spelling makes way for it (see SPELLING_RULES and the lists after it).

Usage:  python3 tools/build_dictionary.py path/to/en_US_wordlist.combined.gz path/to/en_GB_wordlist.combined.gz
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

# British spellings and their American forms, as rules on whole words: a word in the
# en_GB list whose American form is in the US list, one of them missing from the
# other list, is the same word spelled two ways.
SPELLING_RULES = [
    ("our", "or"), ("ise", "ize"), ("ised", "ized"), ("ises", "izes"), ("ising", "izing"),
    ("isation", "ization"), ("isations", "izations"), ("iser", "izer"), ("isers", "izers"),
    ("yse", "yze"), ("ysed", "yzed"), ("yses", "yzes"), ("ysing", "yzing"),
    ("lled", "led"), ("lling", "ling"), ("ller", "ler"), ("llers", "lers"),
    ("tre", "ter"), ("tres", "ters"), ("tred", "tered"), ("ogue", "og"), ("ogues", "ogs"),
    ("ence", "ense"), ("ences", "enses"), ("grey", "gray"),
]

# Words the rules would pair that are different words.
NOT_SPELLINGS = {("bourn", "born"), ("licenced", "licensed")}

# American forms British English uses too: they stay, ranked under the British one.
# (A program runs on a computer, a meter measures gas, and to license is the verb.)
KEEP_AMERICAN = {
    "program", "programs", "meter", "meters", "micrometer", "micrometers", "license", "licenses",
    "dialog", "dialogs", "analog",
}

# Pairs British English has both of, the second first: (American, British).
BRITISH_FIRST = [
    ("mom", "mum"), ("moms", "mums"), ("math", "maths"), ("cozy", "cosy"), ("fulfill", "fulfil"),
    ("enroll", "enrol"), ("enrollment", "enrolment"), ("skillful", "skilful"), ("willful", "wilful"),
    ("installment", "instalment"), ("airplane", "aeroplane"), ("airplanes", "aeroplanes"),
    ("pajamas", "pyjamas"), ("aluminum", "aluminium"), ("jewelry", "jewellery"), ("plow", "plough"),
    ("mustache", "moustache"), ("donut", "doughnut"), ("donuts", "doughnuts"),
    ("skeptical", "sceptical"), ("skeptic", "sceptic"), ("skepticism", "scepticism"),
    ("mold", "mould"), ("molds", "moulds"), ("moldy", "mouldy"), ("program", "programme"),
    ("programs", "programmes"), ("judgment", "judgement"), ("judgments", "judgements"),
    ("pediatric", "paediatric"), ("anesthetic", "anaesthetic"), ("tire", "tyre"),
    ("tires", "tyres"), ("curb", "kerb"), ("aging", "ageing"), ("labeled", "labelled"),
    ("labeling", "labelling"), ("signaled", "signalled"), ("signaling", "signalling"),
    ("fueled", "fuelled"), ("fueling", "fuelling"), ("counseling", "counselling"), ("grueling", "gruelling"),
]

# How far below its British form an American spelling that stays is ranked.
BRITISH_LEAD = 15


def parse_attrs(line):
    attrs = {}
    for part in line.strip().split(","):
        if "=" in part:
            key, value = part.split("=", 1)
            attrs[key] = value
    return attrs


def utf16_key(s):
    return s.encode("utf-16-be")


def read_list(path):
    """An AOSP word list: its locale, {word: (frequency, offensive)} and shortcuts."""
    opener = gzip.open if path.endswith(".gz") else open
    words = {}
    shortcuts = []
    current = None
    locale = None
    with opener(path, "rt", encoding="utf-8") as f:
        for raw in f:
            if raw.startswith("dictionary="):
                locale = parse_attrs(raw).get("locale")
            elif raw.startswith(" word="):
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
    return locale, words, shortcuts


def american_forms(word):
    """Every way SPELLING_RULES can respell [word] in American."""
    forms = set()
    for british, american in SPELLING_RULES:
        i = word.find(british)
        while i >= 0:
            forms.add(word[:i] + american + word[i + len(british):])
            i = word.find(british, i + 1)
    return forms


def british(us, gb):
    """English (UK) words, and {American: British} for the spellings that changed."""
    words = dict(us)
    for word, entry in gb.items():
        words.setdefault(word, entry)
    respelled = {}
    for word in sorted(gb):
        if not word.islower():
            continue
        for american in sorted(american_forms(word)):
            # A regional pair: one of the two is missing from the other list
            # (unlike "four" and "for", both everywhere).
            regional = word not in us or american not in gb
            if american in us and regional and (word, american) not in NOT_SPELLINGS:
                respelled[american] = word
    for american, word in BRITISH_FIRST:
        if american in words and word in words:
            respelled[american] = word
    both = KEEP_AMERICAN | {american for american, _ in BRITISH_FIRST}
    for american, word in respelled.items():
        freq = max(words[american][0], words[word][0])
        words[word] = (freq, words[word][1])
        if american in both:
            words[american] = (min(words[american][0], max(0, freq - BRITISH_LEAD)), words[american][1])
        else:
            del words[american]
    return words, respelled


def write_dictionary(locale, words, shortcuts):
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, f"{locale}.txt"), "w", encoding="utf-8", newline="\n") as out:
        for word in sorted(words, key=utf16_key):
            freq, offensive = words[word]
            out.write(f"{word}\t{freq}\to\n" if offensive else f"{word}\t{freq}\n")
    with open(os.path.join(OUT_DIR, f"{locale}_shortcuts.txt"), "w", encoding="utf-8", newline="\n") as out:
        for source, replacement, mode in sorted(set(shortcuts)):
            out.write(f"{source}\t{replacement}\t{mode}\n")
    print(f"{locale}: {len(words)} words, {len(shortcuts)} shortcuts written to {OUT_DIR}")


def write_british_predictions(respelled):
    """en_US_predictions.txt with every word in British spelling."""
    def respell(token):
        word = respelled.get(token.lower())
        if word is None:
            return token
        return word[0].upper() + word[1:] if token[0].isupper() else word

    source = os.path.join(OUT_DIR, "en_US_predictions.txt")
    with open(source, encoding="utf-8") as f, \
            open(os.path.join(OUT_DIR, "en_GB_predictions.txt"), "w", encoding="utf-8", newline="\n") as out:
        for line in f:
            if line.startswith("#"):
                out.write(line.replace("for English.", "for English (UK), from en_US_predictions.txt."))
                continue
            out.write("\t".join(" ".join(respell(t) for t in part.split(" ")) for part in line.rstrip("\n").split("\t")) + "\n")


def main(us_path, gb_path):
    _, us, us_shortcuts = read_list(us_path)
    _, gb, gb_shortcuts = read_list(gb_path)
    write_dictionary("en_US", us, us_shortcuts)
    words, respelled = british(us, gb)
    write_dictionary("en_GB", words, gb_shortcuts)
    write_british_predictions(respelled)
    print(f"en_GB: {len(respelled)} American spellings ranked under or replaced by British ones")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
