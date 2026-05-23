# API Code

Use this reference for implementation under `E:\AstralRecord-Workspace\20_api\AstralRecordApi`.

## Required Reads

1. `E:\AstralRecord-Workspace\AGENTS.md`.
2. `E:\AstralRecord-Workspace\README.md` AstralRecord API section.
3. Relevant detailed docs under `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\`.

## Responsibilities

1. Keep endpoint definitions in `Controllers/`.
2. Keep request and response DTOs in `Models/`.
3. Keep DB entities in `Data/Entities/`.
4. Keep persistence access in `Repositories/`, pairing `I<Feature>Repository` with `<Feature>Repository`.
5. Keep authentication, options, and shared utilities in their established directories.

## Runtime and Settings

1. Runtime is .NET 10.
2. Framework is ASP.NET Core Web API.
3. Mutable data is read from SQL Server.
4. Static data is read from file-system data definitions.
5. Settings are managed by `AstralRecordApi/appsettings.json` and `AstralRecordApi/appsettings.Development.json`.
6. `ConnectionStrings:SqlServer` is the SQL Server connection string.
7. `FileDatabase:RootPath` is the static data root path.

## API Change Rules

Use these rules when adding APIs, changing endpoint contracts, or updating API documentation.

1. Keep Controller, DTO, Repository, and Entity responsibilities separate.
2. Do not put persistence logic in Controllers.
3. Do not reuse Entities as DTOs.
4. Treat API contract changes as changes that may affect Plugin, Web, Database, and Filebase.
5. Update the root `README.md` AstralRecord API endpoint list when API endpoints are added or changed.
6. Update detailed API design docs under `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` when they exist for the changed endpoint.
7. Update Controller XML doc comments (`/// <summary>`) when adding or changing endpoints.
8. Review sample requests, response examples, and explanatory text when contracts change.
9. Follow the existing `ApiKeyAuthenticationHandler` pattern for authentication.

## Verification

1. Prefer targeted tests for the changed Controller/Service/Repository.
2. Use `dotnet build` from `E:\AstralRecord-Workspace\20_api\AstralRecordApi` when the change affects compile-time contracts.
3. For Database/Filebase-backed endpoints, also check the corresponding definitions under `00_docs/40_Database設計書` or `40_filebase`.
