# ADR-0001: Java 21 + Spring Boot for the backend instead of Python + FastAPI

- **Status:** Accepted
- **Date:** 2026-10-15
- **Deciders:** _Team. Add names/handles._

## Decision

The backend is written in **Java 21** with **Spring Boot** (4.1.1 at the time of writing) and built with Maven. The deterministic scheduling engine is a plain-Java module inside this backend.

## Context

- The core of the product is a **deterministic constraint-satisfaction engine** (section selection with time-conflict checks, credit bounds and weighted soft preferences). It's CPU-bound, and correctness matters more than prototyping speed.
- Four developers work in parallel on separate modules: Backend API, Scheduling Engine, AI Integration, Data/Frontend. Clear, compiler-checked interfaces between modules reduce integration bugs.
- The backend must call the Anthropic Claude API, validate the output, and expose a JSON REST API to a Next.js frontend.
- It's a hackathon, so the stack must be quick to set up on teammates' Macs and well supported by AI coding agents.

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| **Java 21 + Spring Boot** (chosen) | Static types and records for the domain model; fast JVM for search-heavy code; mature validation, data access and testing (`@WebMvcTest`, MockMvc); official Anthropic Java SDK; Maven Wrapper gives reproducible builds | More boilerplate than Python; slower edit-run loop; Spring Boot 4 is new, so many online examples are for 3.x |
| **Python + FastAPI** | Very fast to prototype; Pydantic models give validation plus an auto-generated OpenAPI spec; large AI/ML ecosystem; official Anthropic Python SDK | Type hints aren't enforced at runtime outside Pydantic; slower for tight search loops in pure Python; the engine's correctness depends more on test discipline |
| **TypeScript (Node, e.g. NestJS) for the whole stack** | One language across frontend and backend; types could be shared directly | The engine would run on a single-threaded event loop; less mature constraint-solving options; doesn't match the stack the team chose |
| **Kotlin + Spring Boot** | Same ecosystem with less boilerplate | Another language for teammates to learn; less material for onboarding |

## Reasoning

1. **The engine is the riskiest component, and static typing helps most there.** Immutable records, enums such as `DayOfWeek`, and compile-time checks catch mistakes in meeting-time and constraint logic before tests run.
2. **Performance headroom.** Backtracking search with pruning is a tight loop, and the JVM handles it comfortably. In Python it might need native extensions or a solver library sooner.
3. **Spring provides what we'd otherwise build ourselves:** request validation, error handling (Problem Details), data access, and fast HTTP slice tests.
4. **AI integration doesn't require Python.** Anthropic ships an official Java SDK (`com.anthropic:anthropic-java`) that supports structured outputs, so Java gives up nothing important for our AI use cases (extraction and explanation, not model training).
5. **Team fit:** _Team to confirm, e.g. members' prior Java/Spring experience or course background._

## Consequences

- ✅ The engine can be unit-tested as pure Java with no framework.
- ✅ Every teammate builds with only a JDK, using `./mvnw`.
- ⚠️ No shared types between the Java backend and the TypeScript frontend. [api-contract.md](../api-contract.md) is the source of truth, and both sides must stay in sync manually. Follow-up to consider: generate an OpenAPI spec (e.g. springdoc-openapi) and TypeScript types from it.
- ⚠️ Spring Boot 4 differs from most online examples (modular starters, Jackson 3, moved test annotations). This is documented in [CLAUDE.md](../../CLAUDE.md) so humans and AI agents don't apply 3.x patterns.
- ⚠️ When the Anthropic SDK is added, check its Jackson dependency against Boot 4's Jackson 3.
