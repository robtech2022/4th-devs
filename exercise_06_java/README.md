# Exercise 06 - Item Classification Task

## Task Description

Classify 10 items as dangerous (DNG) or neutral (NEU) using a severely token-limited system (100 tokens max). The challenge includes a special requirement: reactor parts must always be classified as NEU to avoid inspection, even if they appear dangerous.

## Solution

The application:
1. Downloads CSV file with 10 items from the hub
2. Resets the 1.5 PP budget
3. Tries multiple prompt variations until successful
4. Sends classification requests for each item
5. Handles errors and retries with different prompts

## Successful Prompt

```
Reply with DNG if item is weapon/explosive. Reply NEU if reactor/tool/safe. Item {id}:{description}
```

This prompt:
- Clearly identifies dangerous items (weapons/explosives)
- Explicitly includes reactor in the NEU category
- Stays well under the 100 token limit
- Successfully classifies all 10 items correctly

## Result

**Flag obtained: {FLG:SMUGGLER}**

All 10 items classified correctly within the budget constraints.

## Running the Application

```bash
cd exercise_06_java
mvn compile exec:java
```

## Key Learnings

1. **Brevity is critical** - Every word counts when working with strict token limits
2. **Explicit instructions** - The model needs clear guidance on edge cases (reactor parts)
3. **Iterative approach** - Multiple prompt variations increase success rate
4. **Budget management** - Reset functionality is essential for multiple attempts
