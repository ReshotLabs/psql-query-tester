# PSQL Query Tester - JetBrains Plugin

JetBrains IDE plugin (PyCharm, IntelliJ, WebStorm, etc.) for AI-powered SQL query extraction, testing, and optimization.

## Installation

### From Release
1. Download the latest `.zip` from [Releases](https://github.com/ReshotLabs/psql-query-tester/releases)
2. In your IDE: **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk...**
3. Select the `.zip` file and restart

### Build from Source
```bash
# Requires Java 17+
./gradlew buildPlugin

# Plugin at: build/distributions/psql-query-tester-1.0.0.zip
```

## Configuration

**Settings** → **PSQL Query Tester**:

| Setting | Description |
|---------|-------------|
| **OpenRouter API Key** | From [openrouter.ai/keys](https://openrouter.ai/keys) |
| **Model Override** | Optional custom model (e.g., `anthropic/claude-sonnet-4`) |
| **PostgreSQL Connection** | `postgresql://user:pass@host:port/db` |
| **Max Result Rows** | Default: 1000 |
| **Query Timeout** | Default: 30 seconds |
| **SSL Mode** | `disable`, `prefer`, `require`, `verify-ca`, `verify-full` |

## Usage

1. Select code containing SQL
2. Press `Ctrl+Shift+P`
3. Fill in parameters (use **AI** button for auto-fill)
4. Click **Execute Query**
5. View results and optimizations
6. Edit & test optimized queries
7. Apply changes back to code

## Keyboard Shortcut

`Ctrl+Shift+P` (all platforms)

## Requirements

- JetBrains IDE 2023.3+
- Java 17+ (for building)
- PostgreSQL database
- OpenRouter API key
