# Bruno collection

Same 26 requests as the Postman collection, in [Bruno](https://www.usebruno.com)'s
plain-text `.bru` format, so the calls live in git and diff like code.

## Open it

1. Bruno → **Open Collection** → select this `bruno/` folder.
2. Pick the **Local** environment (top right).
3. Set `adminToken` to your `GATEWAY_ADMIN_TOKEN` (the environment ships with
   `test-admin`, matching the smoke suite).
4. Run **00 · Setup → Create API key** first. It mints a key and stores it in the
   `apiKey` variable; every other request uses it.

## Layout

| Folder | What |
|---|---|
| `00 · Setup` | create a client key, list providers |
| `01 · Health & catalogue` | health, `/v1/models`, one model |
| `02 · Chat` | non-streaming, streaming, fallback chain, tools, Anthropic |
| `03 · Usage & credits` | the calling key's spend and balance |
| `04 · Admin — providers` | list, create, update, delete |
| `05 · Admin — routes` | list, create, update, delete |
| `06 · Admin — keys` | list, top up, revoke |
| `07 · Admin — reporting` | stats, usage rows, model sync |

Variables: `baseUrl`, `adminToken`, `apiKey`, `model`, `publicModel`, `providerId`,
`keyId`. The create requests also set `createdProviderId` / `createdRouteId` for the
PATCH and DELETE calls.

## Regenerating

The collection mirrors `docs/path.postman_collection.json`. After changing the Postman
collection:

```bash
python3 tools/postman_to_bruno.py
```

## CLI

Run from inside the collection (the CLI treats the current directory as the root):

```bash
cd bruno
npx @usebruno/cli run . -r --env Local
```

Verified against a local gateway: 26 requests, 26 passed. Useful for a smoke run; not
wired into CI because it needs a running instance and a key.
