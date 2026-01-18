# PSQL Query Tester

A JetBrains IDE plugin (PyCharm, IntelliJ, etc.) that extracts SQL queries from code, provides AI-powered query analysis, and lets you test queries directly against your PostgreSQL database.

## Features

### Query Extraction
- Select code containing SQL queries and press `Ctrl+Shift+P`
- AI automatically extracts and formats the SQL query
- Supports Elixir/Ecto, raw SQL strings, and other languages
- Detects query parameters and generates a dynamic input form

### Query Execution
- Execute queries directly against your PostgreSQL database
- See results in a table with timing information
- Copy results to clipboard (cell, row, or entire table)

### AI-Powered Optimization
- After running a query, get AI-suggested optimizations
- See expected performance improvements and explanations
- Edit suggested queries before testing
- Test optimized queries and compare execution times
- Apply optimizations back to your source code

### AI Parameter Assistant
- Click the "AI" button next to any parameter field
- Describe what value you want in plain language:
  - "the oldest user in the database"
  - "any product created in the past 30 days"
  - "the order with the highest total"
- AI generates and executes a query to find the value

### Database Schema Awareness
- AI has access to your database schema
- Validates table and column names
- Suggests queries based on actual schema structure

## Installation

### From Disk
1. Download the latest release `.zip` file
2. In your JetBrains IDE: **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk...**
3. Select the downloaded `.zip` file
4. Restart the IDE

### Build from Source
```bash
# Requires Java 17+
./gradlew buildPlugin

# Plugin will be at: build/distributions/psql-query-tester-1.0.0.zip
```

## Configuration

Go to **Settings** → **PSQL Query Tester**:

| Setting | Description |
|---------|-------------|
| **OpenRouter API Key** | Your API key from [openrouter.ai/keys](https://openrouter.ai/keys) |
| **Model Override** | Optional. Override the default model (e.g., `anthropic/claude-sonnet-4`) |
| **PostgreSQL Connection String** | Format: `postgresql://user:password@host:port/database` |
| **Max Result Rows** | Maximum rows to return (default: 1000) |
| **Query Timeout** | Timeout in seconds (default: 30) |
| **SSL Mode** | `disable`, `prefer`, `require`, `verify-ca`, `verify-full` |

## Usage

1. **Configure the plugin** in Settings with your OpenRouter API key and database connection
2. **Open any file** containing SQL queries in code
3. **Select the code** containing a query
4. **Press `Ctrl+Shift+P`** (or find "Extract and Test PSQL Query" in the Tools menu)
5. The **PSQL Query Tester** tool window opens with:
   - Extracted and formatted SQL query
   - Parameter input form (if parameters detected)
   - Execute button to run the query
6. **Fill in parameters** and click **Execute Query**
7. **View results** and optimization suggestions
8. **Test optimizations** and apply them to your code

## Keyboard Shortcut

- **Windows/Linux**: `Ctrl+Shift+P`
- **macOS**: `Ctrl+Shift+P`

## Supported Languages

The AI can extract SQL from various languages including:
- Elixir (Ecto queries, fragments, raw SQL)
- Python (psycopg2, SQLAlchemy, raw strings)
- JavaScript/TypeScript (pg, knex, raw strings)
- Ruby (ActiveRecord, raw SQL)
- And more...

## Technology Stack

- **Kotlin** - Plugin implementation
- **IntelliJ Platform SDK** - JetBrains plugin framework
- **Ktor** - HTTP client for OpenRouter API
- **HikariCP** - PostgreSQL connection pooling
- **OpenRouter** - AI model routing (Claude, GPT, etc.)

## Requirements

- JetBrains IDE 2023.3 or newer (PyCharm, IntelliJ IDEA, WebStorm, etc.)
- PostgreSQL database
- OpenRouter API key

## License

MIT

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.
