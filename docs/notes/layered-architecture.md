# Layered Architecture (Controller / Service / Repository)

**Universal backend pattern.** Same concept, different names per framework.

## The Three Layers

| Layer | Job | Talks to | Lives at |
|---|---|---|---|
| **Controller** | HTTP boundary — parse requests, return responses, map to HTTP status codes | Service | `@RestController` (Spring), Router (Express), Views (Django) |
| **Service** | Business logic — validation, orchestration, "the rules" | Repository + other Services | `@Service` (Spring), Service class (any) |
| **Repository** | Data access — talks to database, nothing else | Database only | `@Repository` (Spring), Model manager (Django), Repository (NestJS) |

## Why Separate Them

Each layer changes for different reasons:
- HTTP format changes → touch Controller only
- Business rule changes → touch Service only
- Switch databases → touch Repository only

If you find yourself doing DB queries in the controller, you broke the pattern.

## Cross-Language Names

| Java (Spring) | Python (FastAPI) | Node.js (NestJS) | .NET |
|---|---|---|---|
| `@Controller` / `@RestController` | Router / View | `@Controller()` | `[ApiController]` |
| `@Service` | Service class | `@Injectable()` | `Service` |
| `@Repository` | Model manager | `@Repository()` | `IRepository` |

## Golden Rule

Controllers do HTTP. Services do business rules. Repositories do queries.  
Never mix. If a controller has a SQL query, refactor.