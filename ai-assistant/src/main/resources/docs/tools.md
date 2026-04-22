# External Tools — How to Create and Use Them

External tools extend the assistant beyond the built-in core. They're defined as JSON files
under `data/project/tools/` and made available to the agent via a meta-tool registry.

## How the agent uses them at runtime

Four meta-tools are always available:

- `findTools(query)` — keyword search over registered external tools (name + description).
- `describeTool(name)` — get full parameter schema before calling.
- `invokeTool(name, jsonArgs)` — invoke the tool with a JSON argument object.
- `reloadTools()` — re-read `data/project/tools/*.json` after you added or edited one.

Typical flow: `findTools("weather")` → `describeTool("currentWeather")` →
`invokeTool("currentWeather", '{"city":"Berlin"}')`.

Delegation tip: if you're researching which tool to use, consider doing the whole discovery
in a `subtask(...)` — that keeps the main conversation clean.

## Directory layout

```
data/project/tools/
  weather.json
  myapi.json
  github.json
  ...
```

All files with `.json` extension are scanned. The `type` field inside decides which
**flavor** handles the tool.

## Flavor: rest

Fields in the JSON:

| Field | Required | Meaning |
|---|---|---|
| `type` | yes | `"rest"` |
| `name` | yes | Unique tool name used by the agent |
| `description` | recommended | Plain-text description for the LLM |
| `method` | no | HTTP method, default `GET` |
| `urlTemplate` | yes | URL with `{placeholder}` substitutions (values URL-encoded) |
| `headers` | no | Map of header name → value (literal) |
| `body` | no | Raw body template with `{placeholder}` substitutions (not encoded) |
| `params` | no | Map describing parameters for the LLM (keys = arg names) |

### Placeholder substitution

`urlTemplate: "https://api.example.com/users/{userId}/posts"` with args
`{"userId": "42"}` becomes `https://api.example.com/users/42/posts`. URL values are
URL-encoded; body values are inserted literally.

### Minimal working example (no auth needed)

```json
{
  "type": "rest",
  "name": "currentWeather",
  "description": "Get current weather for a city as a short string.",
  "method": "GET",
  "urlTemplate": "https://wttr.in/{city}?format=3",
  "params": {
    "city": { "type": "string", "description": "City name, e.g. 'Berlin'" }
  }
}
```

### Example with auth

```json
{
  "type": "rest",
  "name": "githubUser",
  "description": "Fetch a GitHub user profile.",
  "method": "GET",
  "urlTemplate": "https://api.github.com/users/{username}",
  "headers": {
    "Authorization": "Bearer ghp_xxxxxxxxxxxxxx",
    "Accept": "application/vnd.github+json"
  },
  "params": {
    "username": { "type": "string", "description": "GitHub username." }
  }
}
```

### POST with body template

```json
{
  "type": "rest",
  "name": "createGist",
  "description": "Create a public gist.",
  "method": "POST",
  "urlTemplate": "https://api.github.com/gists",
  "headers": { "Authorization": "Bearer ...", "Accept": "application/vnd.github+json" },
  "body": "{\"description\":\"{desc}\",\"public\":true,\"files\":{\"{filename}\":{\"content\":\"{content}\"}}}",
  "params": {
    "desc":     { "type": "string" },
    "filename": { "type": "string" },
    "content":  { "type": "string" }
  }
}
```

Content-Type defaults to `application/json` when `body` is set and no explicit header is given.

## Adding a new tool while the assistant is running

1. Write `data/project/tools/mytool.json`.
2. Call `reloadTools()` — you'll get a summary of loaded/failed configs.
3. The tool is now available via `findTools`/`describeTool`/`invokeTool`.

## Errors you may encounter

- `missing 'type'` — the JSON lacks the `type` field.
- `no flavor registered for type 'X'` — typo or unsupported flavor.
- `duplicate tool name 'foo' (skipped)` — two configs expose the same name.
- Tool returns `ERROR: HTTP 4xx` — the upstream API rejected the request.

## Other flavors

Currently only `rest` is implemented. MCP is planned as a separate flavor — it will use the
same registry and discovery tools; configs will have `"type": "mcp"` and reference an MCP
server command/endpoint. Stateful connections will be managed by the flavor itself.
