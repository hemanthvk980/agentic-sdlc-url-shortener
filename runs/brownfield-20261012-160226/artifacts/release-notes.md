## brownfield-20261012-160226

Additive endpoint POST /api/v1/links/batch (1-50 entries). Validate every entry up front (collect index → reason), then insert all via the existing create() inside one transaction. 201 with links in request order; 400 problem detail with errors map; 409 if an alias is taken (whole batch rolled back).

- `POST /api/v1/links/batch`
