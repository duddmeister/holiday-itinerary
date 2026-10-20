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

Frequencies: AOSP's lists come from formal writing, so they undersell everyday words
("little", "you", "know") and oversell bookish ones ("title", "this"). Given the
Taskmaster dialogs and Project Gutenberg books that tools/build_bigrams.py uses, a word
moves toward where both put it when both rank it higher than the list does, or both
lower (see adjust_to_corpora). Words only one source favours (movie tickets, "thou")
stay put. Run tools/build_bigrams.py afterwards.

Usage:  python3 tools/build_dictionary.py path/to/en_US_wordlist.combined.gz path/to/en_GB_wordlist.combined.gz \
            [path/to/Taskmaster path/to/books]
"""
import gzip
import os
import sys
from collections import Counter

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
    "nah": 120, "yea": 110, "ppl": 110, "rly": 70, "obv": 80, "obvs": 60, "iirc": 70,
    "convo": 90, "convos": 70, "lolol": 70, "lolz": 50, "hmu": 70, "ngl": 90, "istg": 60,
    "idek": 50, "imma": 90, "ima": 70, "tryna": 80, "finna": 60, "bruh": 100, "fam": 80,
    "lowkey": 90, "highkey": 60, "vibing": 70, "yeet": 50, "welp": 60, "ughh": 50,
    "hmmm": 70, "mmm": 70, "uhh": 60, "umm": 80, "ummm": 50, "ahh": 70, "ahhh": 50,
    "hahah": 60, "lmaoo": 70, "lmaooo": 50, "omgg": 40, "plss": 40, "thanx": 50, "nw": 60,
    "gn": 80, "gnight": 40, "tbf": 70, "tbt": 50, "ftw": 60, "smth": 80, "sth": 50,
    "atm": 90, "tmi": 60, "afk": 70, "gg": 80, "ez": 50, "txt": 80, "txting": 50,
    "insta": 80, "Snapchat": 90, "fb": 70, "tldr": 50, "srsly": 50, "cya": 80, "gtg": 70,
    "otw": 60, "wya": 70, "xoxo": 70, "xo": 60, "bae": 70, "hun": 70, "luv": 60,
    "aight": 70, "ight": 60, "alr": 60, "bcuz": 50, "kk": 80, "k": 80, "tyvm": 50,
    "ilysm": 50, "yh": 50, "dw": 50, "xd": 60, "mb": 60, "abt": 70, "res": 60,
}

# ("ik" and "gl" stay out: each is a slip away from "ok" or "go".)

# Everyday chat words that AOSP's list, built from formal writing, ranks as rare or
# flags by mistake: raised to at least this frequency, and offered again. Rare words
# can be taken for slips ("nope" for "hope"), so these need their real standing.
CHAT_WORDS = {
    "yeah": 140, "yep": 110, "yup": 100, "nope": 110, "haha": 120, "hehe": 100, "hmm": 100,
    "hah": 70, "pls": 110, "def": 80, "prob": 70, "probs": 60, "asap": 110, "cos": 70,
    "coz": 60, "hon": 70, "cmon": 70, "gonna": 120, "wanna": 115, "gotta": 100, "kinda": 110,
    "sorta": 80, "dunno": 90, "lemme": 90, "gimme": 80, "oof": 70, "yikes": 80, "okie": 60,
    "ugh": 90, "oops": 90, "whoops": 70, "yay": 100, "ooh": 80, "bro": 100, "sis": 80,
    "babe": 80, "aw": 100, "ya": 90, "ha": 90, "ow": 70, "hi": 130, "Hi": 120,
    # Apps everyone names, which the list predates or undersells.
    "Instagram": 110, "WhatsApp": 110, "Spotify": 100, "Netflix": 100, "Facebook": 110,
    "Twitter": 100, "YouTube": 120, "Google": 120, "iPhone": 110,
}

# Contractions typed without the apostrophe, beyond those in AOSP's list. Where the
# typed form is a word too ("were"), the contraction is only offered, never forced.
EXTRA_SHORTCUTS = {
    "hes": "he's", "shes": "she's", "theyre": "they're", "theyll": "they'll", "theyve": "they've",
    "theyd": "they'd", "youll": "you'll", "youve": "you've", "youre": "you're", "whos": "who's",
    "weve": "we've", "werent": "weren't", "hasnt": "hasn't", "havent": "haven't", "hadnt": "hadn't",
    "doesnt": "doesn't", "didnt": "didn't", "isnt": "isn't", "wasnt": "wasn't", "wouldnt": "wouldn't",
    "couldnt": "couldn't", "shouldnt": "shouldn't", "wouldve": "would've", "couldve": "could've",
    "shouldve": "should've", "mightve": "might've", "mustve": "must've", "itll": "it'll",
    "thatll": "that'll", "theres": "there's", "thats": "that's", "mustnt": "mustn't",
    "neednt": "needn't", "shant": "shan't", "ive": "I've", "dont": "don't",
    "were": "we're", "wed": "we'd", "shell": "she'll", "hell": "he'll", "well": "we'll", "its": "it's",
    # Two words run together.
    "abit": "a bit", "alittle": "a little", "aswell": "as well", "atleast": "at least", "atall": "at all",
    "infact": "in fact", "incase": "in case", "infront": "in front", "inspite": "in spite",
    "eachother": "each other", "noone": "no one", "thankyou": "thank you", "ofcourse": "of course",
    "ontop": "on top", "everytime": "every time",
}

# Recognised, so never "corrected" into something else, but never offered either.
EXTRA_OFFENSIVE = {"wtf": 90, "stfu": 50, "ffs": 50, "fml": 50, "omfg": 40, "idgaf": 40, "tf": 50}
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


# A word moves toward where both corpora put it by the smaller of their two shifts, at
# most this far, once each has seen it this often.
CORPUS_SHIFT = 30
CORPUS_MIN_COUNT = 20


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
    for word, freq in CHAT_WORDS.items():
        words[word] = (max(words.get(word, (0, False))[0], freq), False)
    for word, freq in EXTRA_OFFENSIVE.items():
        words.setdefault(word, (freq, True))
    have = {source for source, _, _ in shortcuts}
    for source, replacement in EXTRA_SHORTCUTS.items():
        if source not in have:
            shortcuts.append((source, replacement, "s" if source in words else "a"))
    return locale, words, shortcuts


def corpus_ranks(counts, freqs):
    """{word: the list's frequency at the rank [counts] give the word}, for words seen enough."""
    seen = [w for w in freqs if counts.get(w, 0) >= CORPUS_MIN_COUNT]
    ladder = sorted((freqs[w] for w in seen), reverse=True)
    return {w: ladder[i] for i, w in enumerate(sorted(seen, key=lambda w: (-counts[w], w)))}


def adjust_to_corpora(words, other, taskmaster, books):
    """Moves frequencies in [words] toward where everyday dialogs and books both put them."""
    from build_bigrams import WEIGHTS, respellings, source_counts  # it imports this module
    capital = Counter()
    for word, (freq, _) in words.items():
        if not word.islower():
            capital[word.lower()] = max(capital[word.lower()], freq)
    # Counts can't tell "will" from "Will", so words whose capitalised form is commoner sit
    # out, as do the chat words above, whose standing is set by hand. "I'm" and the like
    # have their capital only for "I", so they count. Words are counted in lower case.
    listed = {}
    for word, (freq, _) in words.items():
        key = word.lower()
        if freq <= 0 or word in CHAT_WORDS or word in EXTRA_WORDS:
            continue
        if word.islower() and freq > capital[key]:
            listed[key] = word
        elif word.startswith("I'") and word[2:].islower() and key not in words:
            listed[key] = word
    freqs = {key: words[word][0] for key, word in listed.items()}
    vocab = {word.lower(): word.lower() for word in words}
    counts = source_counts(taskmaster, books, vocab, respellings(vocab, {word.lower(): word.lower() for word in other}))
    talk = Counter()
    for source in ("tm12", "tm3"):
        for word, c in counts[source].items():
            talk[word] += WEIGHTS[source] * c
    by_talk = corpus_ranks(talk, freqs)
    by_books = corpus_ranks(counts["books"], freqs)
    moved = 0
    for key, freq in freqs.items():
        if key not in by_talk or key not in by_books:
            continue
        a, b = by_talk[key] - freq, by_books[key] - freq
        if a > 0 and b > 0:
            shift = min(a, b, CORPUS_SHIFT)
        elif a < 0 and b < 0:
            shift = max(a, b, -CORPUS_SHIFT)
        else:
            continue
        word = listed[key]
        words[word] = (max(1, min(255, freq + shift)), words[word][1])
        moved += 1
    return moved


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


def main(us_path, gb_path, taskmaster=None, books=None):
    _, us, us_shortcuts = read_list(us_path)
    _, gb, gb_shortcuts = read_list(gb_path)
    if taskmaster:
        moved = adjust_to_corpora(us, gb, taskmaster, books), adjust_to_corpora(gb, us, taskmaster, books)
        print(f"Frequencies moved toward the corpora: {moved[0]} (en_US list), {moved[1]} (en_GB list)")
    write_dictionary("en_US", us, us_shortcuts)
    words, respelled = british(us, gb)
    write_dictionary("en_GB", words, gb_shortcuts)
    write_british_predictions(respelled)
    print(f"en_GB: {len(respelled)} American spellings ranked under or replaced by British ones")


if __name__ == "__main__":
    if len(sys.argv) not in (3, 5):
        sys.exit(__doc__)
    main(*sys.argv[1:])
