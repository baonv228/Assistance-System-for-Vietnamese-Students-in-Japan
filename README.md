# Assistance System for Vietnamese Students in Japan

Spring Boot REST API with static HTML/CSS/JavaScript frontend.

## Architecture

- `src/main/java/com/sep490/vsa/common`: shared config, response DTOs, exception handling, seed data.
- `src/main/java/com/sep490/vsa/feature`: feature-based backend packages.
- `src/main/resources/static/pages`: frontend pages.
- `src/main/resources/static/components`: reusable HTML components.
- `src/main/resources/static/assets`: CSS and JavaScript.

## Run

```bash
mvn spring-boot:run
```

Then open:

- Frontend: `http://localhost:8080`
- API guides: `http://localhost:8080/api/guides`
- API scholarships: `http://localhost:8080/api/scholarships`
- H2 console: `http://localhost:8080/h2-console`

H2 JDBC URL: `jdbc:h2:mem:vsa_db`

## Demo Account

- Username: `demo`
- Password: `123456`

Login API:

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "demo",
  "password": "123456"
}
```
