#!/usr/bin/env python3
"""Regenerate the Bruno collection from the Postman collection.

    python3 tools/postman_to_bruno.py

Reads `docs/path.postman_collection.json` and rewrites `bruno/` so the two stay in
sync. Postman-specific bits are normalised: `{{$timestamp}}` becomes a fixed name and
"Postman" wording becomes "Bruno". Scripts that captured variables in Postman are
translated to Bruno's `bru.setVar`.
"""
import json
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "docs", "path.postman_collection.json")
TARGET = os.path.join(ROOT, "bruno")

# Requests whose testing scripts set collection variables in Postman.
POST_RESPONSE = {
    "Create API key": 'bru.setVar("apiKey", res.body.key);\nbru.setVar("keyId", res.body.id);',
    "Create provider": 'bru.setVar("createdProviderId", res.body.id);',
    "Create route": 'bru.setVar("createdRouteId", res.body.id);',
}

ENVIRONMENT = """vars {
  baseUrl: http://localhost:8080
  adminToken: test-admin
  apiKey:
  model: gpt-4o-mini
  publicModel: cheap
  providerId: 1
  keyId: 1
}
"""


def normalise(text):
    if text is None:
        return None
    return (text.replace("postman-{{$timestamp}}", "bruno-demo")
                .replace("{{$timestamp}}", "bruno-demo")
                .replace("Postman console", "Bruno's console")
                .replace("Postman", "Bruno"))


def safe_name(name):
    return name.replace("/", "-").replace(":", "-")


def render(item, seq):
    request = item["request"]
    method = request["method"].lower()
    url = normalise(request["url"]["raw"])
    headers = request.get("header", [])
    body = normalise(request.get("body", {}).get("raw"))
    description = normalise((request.get("description") or "").strip())

    blocks = [f"meta {{\n  name: {item['name']}\n  type: http\n  seq: {seq}\n}}"]

    verb = f"{method} {{\n  url: {url}\n"
    if body:
        verb += "  body: json\n"
    verb += "  auth: none\n}"
    blocks.append(verb)

    if headers:
        lines = "\n".join(f"  {header['key']}: {normalise(header['value'])}" for header in headers)
        blocks.append(f"headers {{\n{lines}\n}}")

    if body:
        blocks.append(f"body:json {{\n  {body.replace(chr(10), chr(10) + '  ')}\n}}")

    if description:
        blocks.append(f"docs {{\n  {description.replace(chr(10), chr(10) + '  ')}\n}}")

    script = POST_RESPONSE.get(item["name"])
    if script:
        spaced = script.replace("\n", "\n  ")
        blocks.append(f"script:post-response {{\n  {spaced}\n}}")

    return "\n\n".join(blocks) + "\n"


def walk(items, directory):
    os.makedirs(directory, exist_ok=True)
    for seq, item in enumerate(items, start=1):
        if "item" in item:
            walk(item["item"], os.path.join(directory, safe_name(item["name"])))
        else:
            path = os.path.join(directory, safe_name(item["name"]) + ".bru")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(render(item, seq))


def main():
    # Regenerate only the generated parts; keep the hand-written README.
    os.makedirs(TARGET, exist_ok=True)
    for entry in os.listdir(TARGET):
        if entry == "README.md":
            continue
        path = os.path.join(TARGET, entry)
        shutil.rmtree(path) if os.path.isdir(path) else os.remove(path)
    os.makedirs(os.path.join(TARGET, "environments"), exist_ok=True)

    with open(os.path.join(TARGET, "bruno.json"), "w", encoding="utf-8") as fh:
        json.dump({"version": "1", "name": "Path", "type": "collection",
                   "ignore": ["node_modules", ".git", ".DS_Store"]}, fh, indent=2)
        fh.write("\n")

    with open(os.path.join(TARGET, "environments", "Local.bru"), "w", encoding="utf-8") as fh:
        fh.write(ENVIRONMENT)

    collection = json.load(open(SOURCE, encoding="utf-8"))
    walk(collection["item"], TARGET)
    print(f"wrote {TARGET}")


if __name__ == "__main__":
    main()
