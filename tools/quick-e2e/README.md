# Quick E2E environment setup

A tool to generate a new E2E environment for Minecraft servers. Can be used to quickly setup a new environment for testing plugins on multiple server types and versions.

In the future, this tool can also be using in a CI/CD pipeline to automatically run tests on new builds or plugin versions.

## TODOs

- [ ] Add system to load configuration from file / command line arguments
- [ ] Add services to install plugins
- [ ] Add service to set port
- [ ] Add option to update an existing E2E environment
- [ ] Add option to delete an existing E2E environment

## Features

Generate a new E2E environment with the following options:
-  **Server type**: paper, folia ...
-  **Server version**: 1.19.4, 1.20.1 ...
-  **Server Build**: latest, a specific build
-  **Pre-installed plugins**: links to modrinth
-  **Custom plugin providers**: build from monorepo
-  **EULA**: true, false
-  **OP**: a specific username

Update an existing E2E environment, if there is a new build or plugin version available.

Delete an existing E2E environment.

## Usage

### Generate a new E2E environment

Command line arguments:
```bash
java -jar quick-e2e.jar 
  --<generate|update|delete>
  --server-type paper
  --server-version 1.21.4
  --server-build latest
  --pre-installed-plugins "viaversion,luckperms,worldedit"
  --custom-plugin-providers "monorepo"
  --eula true
  --op "OliverHD"
```

Configuration file:
```json
{
  "serverType": "paper",
  "serverVersion": "1.21.4",
  "serverBuild": "latest",
  "preInstalledPlugins": ["viaversion", "luckperms", "worldedit"],
  "customPluginProviders": ["monorepo"],
  "eula": true,
  "op": "OliverHD"
}
```

```bash
java -jar quick-e2e.jar --generate --config-file config.json
```