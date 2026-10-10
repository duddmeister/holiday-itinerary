#!/usr/bin/env python3
"""Builds Lumen's emoji assets.

Inputs:
  emoji-test.txt  Unicode emoji test data (https://unicode.org/Public/emoji/),
                  used for the emoji list, CLDR ordering and categories.
  emoji.json      GitHub gemoji database (https://github.com/github/gemoji, MIT),
                  used for search keywords that power emoji suggestions.

Outputs:
  app/src/main/assets/emoji/emoji.txt
      "@group <Category>" header lines followed by one emoji per line. When an emoji
      supports skin tones, the five toned variants follow it on the same line,
      separated by spaces (light -> dark).
  app/src/main/assets/emoji/keywords.txt
      keyword<TAB>emoji emoji emoji  (best match first)

Usage: python3 tools/build_emoji.py path/to/emoji-test.txt path/to/emoji.json
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "emoji")

TONES = [0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF]
SKIPPED_GROUPS = {"Component"}

STOPWORDS = {
    "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "in", "into",
    "is", "it", "its", "of", "on", "or", "the", "to", "with", "without", "face", "button",
    "sign", "symbol", "mark", "man", "woman", "person", "people", "light", "dark", "medium",
    "skin", "tone", "flag", "small", "large", "big", "black", "white", "old", "new",
}

# Everyday words people type that gemoji's keywords miss or rank differently.
CURATED = {
    "lol": "😂 🤣 😆", "haha": "😂 😆 🤣", "hahaha": "😂 🤣 😆", "lmao": "🤣 😂 💀",
    "love": "❤️ 😍 🥰", "loved": "❤️ 🥰 😍", "heart": "❤️ 💕 💖", "happy": "😊 😄 🥳",
    "sad": "😢 😞 😔", "cry": "😭 😢 🥲", "crying": "😭 😢 🥹", "laugh": "😂 😆 🤣",
    "smile": "😊 😄 🙂", "thanks": "🙏 😊 🤗", "thank": "🙏 😊 🤗", "please": "🙏 🥺 😇",
    "sorry": "😔 🙏 😓", "ok": "👌 👍 🆗", "okay": "👌 👍 🙂", "yes": "👍 ✅ 🙌",
    "no": "🙅 ❌ 👎", "cool": "😎 🆒 👌", "wow": "😮 🤩 😲", "omg": "😱 😮 🤯",
    "party": "🎉 🥳 🎊", "birthday": "🎂 🎉 🥳", "congrats": "🎉 🥳 👏",
    "congratulations": "🎉 🥳 👏", "fire": "🔥 🚒 🧯", "hot": "🔥 🥵 🌶️", "cold": "🥶 ❄️ 🧊",
    "kiss": "😘 💋 😚", "hug": "🤗 🫂 ❤️", "think": "🤔 💭 🧐", "thinking": "🤔 💭 🧐",
    "sleep": "😴 💤 🛌", "tired": "😴 🥱 😩", "angry": "😠 😡 🤬", "mad": "😠 😡 🤬",
    "pray": "🙏 📿 ⛪", "clap": "👏 🙌 🎉", "hi": "👋 😊 🙂", "hello": "👋 😊 🙂",
    "bye": "👋 😘 🫡", "goodnight": "🌙 😴 💤", "night": "🌙 🌃 😴", "morning": "☀️ 🌅 ☕",
    "coffee": "☕ 🥐 😌", "beer": "🍺 🍻 🥂", "wine": "🍷 🥂 🍇", "pizza": "🍕 😋 🧀",
    "food": "🍔 🍕 😋", "hungry": "😋 🍔 🍕", "yum": "😋 🤤 🍽️", "dog": "🐶 🐕 🦮",
    "cat": "🐱 🐈 😺", "sun": "☀️ 🌞 😎", "rain": "🌧️ ☔ 💧", "snow": "❄️ ☃️ 🌨️",
    "money": "💰 💸 🤑", "music": "🎵 🎶 🎧", "car": "🚗 🚙 🏎️", "home": "🏠 🏡 🛋️",
    "work": "💼 💻 🏢", "beach": "🏖️ 🌊 ☀️", "holiday": "🏖️ ✈️ 🎄", "travel": "✈️ 🧳 🌍",
    "christmas": "🎄 🎅 🎁", "gift": "🎁 🎀 🥳", "cute": "🥰 🥹 😊", "sick": "🤒 🤢 🤧",
    "scared": "😨 😱 😰", "nervous": "😬 😰 😅", "awkward": "😅 😬 🙃", "shrug": "🤷 🤷‍♂️ 🤷‍♀️",
    "eyes": "👀 😳 🧐", "strong": "💪 🦾 🏋️", "win": "🏆 🥇 🎉", "nice": "👌 👍 😎",
    "great": "👍 🙌 🤩", "good": "👍 👌 😊", "bad": "👎 😞 😬", "perfect": "👌 💯 ✨",
    "hundred": "💯", "sparkle": "✨ 💫 🌟", "star": "⭐ 🌟 ✨", "rocket": "🚀",
    "check": "✅ ✔️ ☑️", "done": "✅ 👍 🎉", "idea": "💡 🤔 ✨", "tea": "🍵 🫖 ☕",
}

TONE_RE = re.compile("[\U0001F3FB-\U0001F3FF]")


def strip_key(s):
    """Comparison key: remove skin tones and variation selectors."""
    return TONE_RE.sub("", s).replace("️", "")


def parse_emoji_test(path):
    groups = []  # [(name, [emoji])]
    group = None
    by_key = {}
    tones = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("# group:"):
                name = line.split(":", 1)[1].strip()
                group = None if name in SKIPPED_GROUPS else (name, [])
                if group:
                    groups.append(group)
                continue
            if not line.strip() or line.startswith("#") or group is None:
                continue
            codes, rest = line.split(";", 1)
            status = rest.split("#", 1)[0].strip()
            if status != "fully-qualified":
                continue
            name = rest.split("#", 1)[1].strip().split(" ", 2)[2]
            if "facing right" in name:
                continue  # directional variants of emoji already in the list
            cps = [int(c, 16) for c in codes.split()]
            emoji = "".join(chr(c) for c in cps)
            used_tones = {c for c in cps if c in TONES}
            if used_tones:
                if len(used_tones) == 1:
                    tones.setdefault(strip_key(emoji), {})[used_tones.pop()] = emoji
                continue
            by_key.setdefault(strip_key(emoji), emoji)
            group[1].append(emoji)
    variants = {}
    for key, toned in tones.items():
        base = by_key.get(key)
        if base and len(toned) == 5:
            variants[base] = [toned[t] for t in TONES]
    return groups, by_key, variants


def build_keywords(gemoji_path, by_key, order):
    data = json.load(open(gemoji_path, encoding="utf-8"))
    scored = {}

    def add(word, emoji, rank):
        word = word.lower()
        if not re.fullmatch(r"[a-z][a-z']+", word) or word in STOPWORDS:
            return
        best = scored.setdefault(word, {})
        if emoji not in best or best[emoji] > rank:
            best[emoji] = rank

    for item in data:
        emoji = by_key.get(strip_key(item["emoji"]))
        if emoji is None:
            continue
        for alias in item.get("aliases", []):
            parts = alias.split("_")
            if len(parts) == 1:
                add(alias, emoji, 0)
            else:
                for part in parts:
                    add(part, emoji, 2)
        for tag in item.get("tags", []):
            add(tag, emoji, 1)
        desc = item.get("description", "").split()
        if len(desc) == 1:
            add(desc[0], emoji, 0)

    keywords = {}
    for word, emojis in scored.items():
        ranked = sorted(emojis, key=lambda e: (emojis[e], order.get(e, 1 << 30)))
        keywords[word] = ranked[:3]
    for word, emojis in CURATED.items():
        keywords[word] = emojis.split()
    return keywords


def main(test_path, gemoji_path):
    groups, by_key, variants = parse_emoji_test(test_path)
    order = {}
    for _, emojis in groups:
        for e in emojis:
            order[e] = len(order)
    os.makedirs(OUT_DIR, exist_ok=True)
    count = 0
    with open(os.path.join(OUT_DIR, "emoji.txt"), "w", encoding="utf-8", newline="\n") as out:
        for name, emojis in groups:
            out.write(f"@group {name}\n")
            for e in emojis:
                out.write(" ".join([e] + variants.get(e, [])) + "\n")
                count += 1
    keywords = build_keywords(gemoji_path, by_key, order)
    with open(os.path.join(OUT_DIR, "keywords.txt"), "w", encoding="utf-8", newline="\n") as out:
        for word in sorted(keywords):
            out.write(f"{word}\t{' '.join(keywords[word])}\n")
    print(f"{count} emoji ({len(variants)} with skin tones), {len(keywords)} keywords -> {OUT_DIR}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
