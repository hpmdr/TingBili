# TingBili

## Codebase Knowledge Graph

This project is indexed as `home-ljq-ssd-code-TingBili` (nodes: ~1210, edges: ~4575) in codebase-memory-mcp. The index lives in `/home/ljq/.cache/codebase-memory-mcp/home-ljq-ssd-code-TingBili.db` and is served by `/home/ljq/.local/bin/codebase-memory-mcp` via ZCode MCP (`~/.zcode/cli/config.json` → `mcp.servers.codebase-memory`). It does NOT depend on opencode — opencode just happens to connect to the same Server via its own Host config.

Prefer graph tools for code discovery; fall back to grep/glob only for string literals, non-code files, or when graph results are insufficient.

Priority: `search_graph` → `trace_path` → `get_code_snippet` → `query_graph` → `get_architecture`.

After major refactors, run `detect_changes` and re-index if needed. The graph excludes `build/`, `.gradle/`, `.git` by design.
