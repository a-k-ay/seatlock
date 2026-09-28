# Swagger UI — Cheatsheet

## What Swagger UI Is

An auto-generated web page at `/swagger-ui/index.html` that lists every 
endpoint in your Spring Boot app. It's the API browser for humans. 
Powered by the `springdoc-openapi` dependency, which scans your 
controllers at boot and emits an OpenAPI 3 spec.

You don't write Swagger docs; they're inferred from:
- Controller class annotations (`@RestController`, `@RequestMapping`)
- Method annotations (`@GetMapping`, `@PostMapping`, etc.)
- Parameter annotations (`@PathVariable`, `@RequestBody`, 
  `@RequestHeader`, `@RequestParam`, `@Valid`)
- DTO fields with `@NotNull`, `@Size`, `@Email` etc. (Bean Validation)

## Access

URL: `http://localhost:8080/swagger-ui/index.html`

Also available: the raw OpenAPI spec at `/v3/api-docs`.

## Sections Explained

- **Each controller** becomes a collapsible group (e.g., 
  `event-controller`, `hold-controller`).
- **Each endpoint** is a colored bar: GET (blue), POST (green), 
  PUT (yellow), DELETE (red).
- **Schemas** at the bottom list every DTO with its field types.

## Authorize (JWT / Bearer)

Requires an `OpenApiConfig` class with `@SecurityScheme(name = 
"bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", 
bearerFormat = "JWT")` — that produces the green **Authorize** button 
top-right.

1. Log in via `POST /auth/login` → copy the `token` field from 
   the response.
2. Click **Authorize** → paste token (no "Bearer " prefix) → 
   Authorize → Close.
3. Every subsequent request from Swagger includes 
   `Authorization: Bearer <token>` automatically.
4. Token stays until you Logout in the dialog or refresh the page.

## Making Requests

For any endpoint, click to expand:
1. **Try it out** → makes the fields editable.
2. Fill in **Parameters** — path variables like `{id}`, query params, 
   headers.
3. Fill in **Request body** if any. Swagger prefills a sample based 
   on the DTO schema.
4. **Execute** → sends the HTTP request.

The response section shows the equivalent `curl` command, request URL, 
status code, response body, and response headers. Copy the curl if 
you want to reproduce outside Swagger.

## Common Patterns

- **UUID path/body params:** Swagger sometimes prefills the default 
  `3fa85f64-5717-4562-b3fc-2c963f66afa6` — remember to replace with 
  a real UUID or requests fail.
- **List endpoints:** wrap items inside a container field, e.g. 
  `{"seats": [...]}` — the schema shows the shape.
- **Custom headers** (like `Idempotency-Key`): appear above the 
  request body when the controller declares them via 
  `@RequestHeader`.
- **Read the curl block:** it's the definitive statement of what 
  was sent — great for debugging.

## When Swagger Lies

- The default sample values are just placeholders, not usable data.
- 4xx status descriptions may be missing if you didn't annotate them 
  (`@ApiResponse`). Undocumented ≠ broken.
- The "Undocumented" label on a real response code just means the 
  spec didn't declare it explicitly. Runtime behavior is what matters.

## When to Use Something Else

- Load / concurrency testing → JMeter, k6, Locust.
- Formal integration tests → Testcontainers + RestAssured.
- Automation scripts → curl / httpie / Postman collections.

Swagger UI is a **manual explorer**, not a testing framework.