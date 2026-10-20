#!/usr/bin/env python3
"""Builds Lumen's word-pair model: how much likelier a word is after another.

Inputs (openly licensed English text, downloaded separately):
  The Taskmaster-1, -2 and -3 dialogs (CC BY 4.0, Google LLC), a clone of
      https://github.com/google-research-datasets/Taskmaster
      Every .json file under it is read; TM-3 is all movie tickets, so it counts for less.
  Project Gutenberg books (public domain), as plain text, in a directory: these 55,
      each https://www.gutenberg.org/cache/epub/N/pgN.txt
      11 12 35 36 45 46 55 74 76 84 98 105 108 120 121 141 145 157 158 160 161 174 204
      215 236 242 244 271 289 308 345 498 514 541 558 730 766 768 834 910 1023 1184 1260
      1342 1400 1448 1450 1661 1874 2097 2641 2852 2946 4517 17396

Output: app/src/main/assets/dict/en_US_bigrams.txt and en_GB_bigrams.txt
  Lines starting "#" are comments. Then one line per first word:
      word<TAB>others<TAB>next score next score ...
  A score is how much likelier the next word is after the first than in general:
  log2(P(next | word) / P(next)), in half bits. "others" is that score for any next
  word not listed: what the listed ones leave, shared out by how common words are.
  "<s>" stands for the start of a sentence. Words are lower case and spelt as in
  that locale's dictionary.

The model interpolates absolute discounting with the word's own frequency, so a
word seldom seen first has scores near 0 and one seen often has strong opinions.

Usage:  python3 tools/build_bigrams.py path/to/Taskmaster path/to/books
"""
import json
import math
import os
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_dictionary import american_forms  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "dict")

# How much each source counts: TM-3 is all movie tickets; the books are older English.
WEIGHTS = {"tm12": 1.0, "tm3": 0.4, "books": 0.6}
START = "<s>"
DISCOUNT = 0.75
# A first word needs this much text after it to have a line...
MIN_FIRST = 30.0
# ...and a pair this much to be listed, keeping the commonest pairs of each word: more
# for words seen often, which have more to say.
MIN_PAIR = 2.0
MAX_PER_WORD = 400
# Scores are kept within these bounds, in half bits.
LOWEST, HIGHEST = -24, 30

WORD = re.compile(r"[a-z]+(?:'[a-z]+)*")
SENTENCE_END = re.compile(r"[.!?;:\n\"()\[\]]+")


def vocabulary(locale):
    """{lower-case word: lower-case word} for every word in the locale's dictionary."""
    words = {}
    with open(os.path.join(DICT_DIR, f"{locale}.txt"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or "\t" not in line:
                continue
            word = line.split("\t", 1)[0].lower()
            words[word] = word
    return words


def respellings(vocab, other):
    """Words of the other spelling that this dictionary spells another way."""
    out = {}
    for word in vocab:
        for form in american_forms(word):
            if form not in vocab and form in other:
                out[form] = word
    for word in other:
        if word in vocab:
            continue
        for form in american_forms(word):
            if form in vocab:
                out[word] = form
    return out


def gutenberg_paragraphs(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    start = text.find("*** START OF")
    end = text.find("*** END OF")
    if start >= 0:
        text = text[text.find("\n", start) + 1:]
    if end >= 0:
        text = text[:end]
    for para in re.split(r"\n\s*\n", text):
        yield " ".join(para.split())


def taskmaster_utterances(root):
    """("tm3" or "tm12", text) for every utterance in the Taskmaster clone at [root]."""
    for folder, _, files in sorted(os.walk(root)):
        for name in sorted(files):
            if not name.endswith(".json"):
                continue
            path = os.path.join(folder, name)
            source = "tm3" if "TM-3" in path else "tm12"
            with open(path, encoding="utf-8") as f:
                dialogs = json.load(f)
            if not isinstance(dialogs, list):
                continue
            for dialog in dialogs:
                for utterance in dialog.get("utterances", []):
                    yield source, utterance.get("text", "")


def sources(taskmaster, books):
    yield from taskmaster_utterances(taskmaster)
    for book in sorted(os.listdir(books)):
        if book.endswith(".txt"):
            for para in gutenberg_paragraphs(os.path.join(books, book)):
                yield "books", para


def count(taskmaster, books, vocab, respelled):
    unigrams = Counter()
    pairs = defaultdict(Counter)
    for source, text in sources(taskmaster, books):
        weight = WEIGHTS[source]
        for sentence in SENTENCE_END.split(text.replace("’", "'").lower()):
            previous = START
            seen = False
            for token in WORD.findall(sentence):
                word = vocab.get(token) or respelled.get(token)
                if word is None:
                    previous = None
                    continue
                unigrams[word] += weight
                if previous is not None:
                    pairs[previous][word] += weight
                previous = word
                seen = True
            if seen:
                unigrams[START] += weight
    return unigrams, pairs


def source_counts(taskmaster, books, vocab, respelled):
    """{source: Counter of each word} in each source, unweighted (for build_dictionary.py)."""
    counts = {source: Counter() for source in WEIGHTS}
    for source, text in sources(taskmaster, books):
        seen = counts[source]
        for token in WORD.findall(text.replace("’", "'").lower()):
            word = vocab.get(token) or respelled.get(token)
            if word is not None:
                seen[word] += 1
    return counts


def half_bits(x):
    return max(LOWEST, min(HIGHEST, round(2 * math.log2(x))))


def write(locale, unigrams, pairs, sizes):
    total = sum(c for w, c in unigrams.items() if w != START)
    lines = []
    entries = 0
    for first in sorted(pairs):
        nexts = pairs[first]
        after = sum(nexts.values())
        if after < MIN_FIRST:
            continue
        unseen = DISCOUNT * len(nexts) / after
        cap = min(MAX_PER_WORD, max(40, int(2 * math.sqrt(after))))
        listed = []
        given = 0.0
        alone = 0.0
        for word, c in nexts.most_common():
            if c < MIN_PAIR or len(listed) == cap:
                break
            p = unigrams[word] / total
            q = (c - DISCOUNT) / after + unseen * p
            given += q
            alone += p
            listed.append(f"{word} {half_bits(q / p)}")
        others = max(1e-6, 1.0 - given) / max(1e-6, 1.0 - alone)
        entries += len(listed)
        lines.append(f"{first}\t{half_bits(others)}\t{' '.join(listed)}")
    path = os.path.join(DICT_DIR, f"{locale}_bigrams.txt")
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("# Lumen word pairs for English. Built by tools/build_bigrams.py from the Taskmaster\n")
        out.write("# dialogs (CC BY 4.0, Google LLC) and Project Gutenberg books (public domain).\n")
        out.write("# word<TAB>others<TAB>next score ...; scores are log2(P(next|word)/P(next)) in half bits.\n")
        out.write("\n".join(lines) + "\n")
    sizes[locale] = (len(lines), entries, os.path.getsize(path))


def main(taskmaster, books):
    us = vocabulary("en_US")
    gb = vocabulary("en_GB")
    sizes = {}
    for locale, vocab, other in (("en_US", us, gb), ("en_GB", gb, us)):
        unigrams, pairs = count(taskmaster, books, vocab, respellings(vocab, other))
        write(locale, unigrams, pairs, sizes)
    for locale, (words, entries, size) in sizes.items():
        print(f"{locale}: {words} first words, {entries} pairs, {size // 1024} KB")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
