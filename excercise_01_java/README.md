# excercise_01_java

Java version of the AG3NTS `people` exercise.

## Requirements

- Java 17+
- Maven 3.9+
- Root `.env` in parent folder with:

```env
OPENROUTER_API_KEY=sk-or-v1-...
AI_PROVIDER=openrouter
AG3NTS_API_KEY=your_hub_key
AG3NTS_AUTO_SUBMIT=false
```

## Run

From project folder:

```bash
mvn -q exec:java
```

Or from workspace root:

```bash
mvn -q -f excercise_01_java/pom.xml exec:java
```

Output payload:

- `excercise_01_java/output/people-answer.txt`
