## Bean Registration
| Annotation | Meaning |
|---|---|
| `@SpringBootApplication` | The entry point — main class |
| `@Component` | Generic Spring-managed bean |
| `@Service` | Business logic layer |
| `@Repository` | Data access layer |
| `@RestController` | REST endpoint handler (returns JSON) |
| `@Controller` | Traditional MVC controller (returns views) |
| `@Configuration` | Class that defines beans |
| `@Bean` | Method producing a bean (inside `@Configuration`) |
## Dependency Injection
| Annotation | Usage |
|---|---|
| `@Autowired` | Inject a bean (older style) |
| `@RequiredArgsConstructor` (Lombok) | Auto-generate constructor for `final` fields — modern preferred way |
## HTTP Mapping (Controllers)
| Annotation | Purpose |
|---|---|
| `@GetMapping("/path")` | GET |
| `@PostMapping("/path")` | POST |
| `@PutMapping("/path")` | PUT |
| `@DeleteMapping("/path")` | DELETE |
| `@RequestMapping("/prefix")` | Common URL prefix on a controller |
| `@PathVariable UUID id` | From URL: `/events/{id}` |
| `@RequestBody CreateRequest req` | From JSON body |
| `@RequestParam String q` | From query string: `?q=foo` |
| `@RequestHeader("X-Foo") String foo` | From HTTP header |
## Validation
| Annotation | Usage |
|---|---|
| `@Valid` | Trigger validation on `@RequestBody` |
| `@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Max` | Bean Validation constraints |
## JPA / Persistence
| Annotation | Meaning |
|---|---|
| `@Entity` | Class maps to DB table |
| `@Table(name = "events")` | Explicit table name |
| `@Id` | Primary key |
| `@GeneratedValue` | Auto-generate ID |
| `@Column(name = "start_time")` | Column name mapping |
| `@Enumerated(EnumType.STRING)` | Store enum as text |
| `@Transactional` | Wrap method in a DB transaction |
| `@Transactional(readOnly = true)` | Read-only transaction (optimization) |
## Lombok (reduces boilerplate)
| Annotation | Generates |
|---|---|
| `@Getter`, `@Setter` | Getter/setter methods |
| `@NoArgsConstructor` | Empty constructor (required by JPA) |
| `@AllArgsConstructor` | Constructor with all fields |
| `@RequiredArgsConstructor` | Constructor with `final` fields (DI) |
| `@Builder` | Builder pattern |
| `@Data` | Everything (⚠ avoid on JPA entities — causes equals/hashCode issues) |
## Configuration
| File | Purpose |
|---|---|
| `application.properties` / `application.yml` | Configuration values |
| `@Value("${property.name}")` | Inject config value |
| `@ConfigurationProperties(prefix = "app")` | Bind whole config section to a class |