# PSQL Query Tester

AI-powered SQL query extraction, testing, and optimization plugins for your IDE.

## Plugins

| Plugin | Status | Description |
|--------|--------|-------------|
| [JetBrains](./jetbrains) | ✅ Ready | PyCharm, IntelliJ IDEA, WebStorm, etc. |
| [VSCode/Cursor](./vscode) | ✅ Ready | Visual Studio Code, Cursor |

## Features

- **Query Extraction** - Select code containing SQL and let AI extract & format it
- **Parameter Detection** - Automatically generates input forms for query parameters
- **Query Execution** - Run queries directly against your PostgreSQL database with timing
- **AI Optimization** - Get AI-suggested query optimizations with explanations
- **AI Parameter Assistant** - Describe values in plain language, AI finds them in your DB
- **Schema Awareness** - AI knows your database structure for accurate suggestions

## Quick Start

### JetBrains (PyCharm, IntelliJ, etc.)

```bash
cd jetbrains
./gradlew buildPlugin
# Install: Settings → Plugins → ⚙️ → Install from Disk → build/distributions/*.zip
```

### VSCode / Cursor

```bash
cd vscode
npm install
npm run package
# Install: Extensions → ... → Install from VSIX → psql-query-tester-1.0.0.vsix
```

## Configuration

Both plugins require:
1. **OpenRouter API Key** - Get one at [openrouter.ai/keys](https://openrouter.ai/keys)
2. **PostgreSQL Connection String** - Format: `postgresql://user:password@host:port/database`

## Usage

1. Select code containing a SQL query
2. Trigger the plugin:
   - **JetBrains**: `Ctrl+Shift+P`
   - **VSCode/Cursor**: `Ctrl+Cmd+T` (Mac) / `Ctrl+Alt+T` (Windows/Linux)
3. Fill in any parameters (use AI button for auto-fill)
4. Click Execute
5. View results and optimization suggestions

## License

MIT
