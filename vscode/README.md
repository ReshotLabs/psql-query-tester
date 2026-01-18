# PSQL Query Tester - VSCode/Cursor Extension

VSCode and Cursor extension for AI-powered SQL query extraction, testing, and optimization.

## Installation

### From VSIX
1. Download the `.vsix` file from releases
2. In VSCode/Cursor: Extensions → ... → Install from VSIX

### Build from Source
```bash
npm install
npm run compile
npm run package
```

## Configuration

**Settings** → search "PSQL Query Tester":

| Setting | Description |
|---------|-------------|
| **openRouterApiKey** | From [openrouter.ai/keys](https://openrouter.ai/keys) |
| **modelOverride** | Optional custom model |
| **connectionString** | `postgresql://user:pass@host:port/db` |
| **maxResultRows** | Default: 1000 |
| **queryTimeout** | Default: 30 seconds |
| **sslMode** | `disable`, `prefer`, `require`, etc. |

## Usage

1. Select code containing SQL
2. Press `Ctrl+Shift+P` → "PSQL: Extract and Test Query"
3. Fill parameters (use **AI** button for auto-fill)
4. Click **Execute Query**
5. View results and optimizations

## Requirements

- VSCode 1.85+ or Cursor
- PostgreSQL database
- OpenRouter API key
