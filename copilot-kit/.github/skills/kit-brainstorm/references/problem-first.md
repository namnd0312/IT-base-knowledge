# Problem-First Brainstorming

Use when the conversation starts from a proposed solution, a half-formed feature idea, or a roadmap of already-chosen work.

## Core move

Treat a proposed solution as a compressed, imprecise statement of a problem the team senses but has not articulated. Do not reject it. Decompress it into the underlying problem, then test whether the proposed solution is one reasonable response among several or a premature jump.

## Triggers

- "We need to build X"
- "Should we add feature Y?"
- "The roadmap already says we need Z"
- "I have too many ideas"
- "Help me triage this idea"
- "The team wants this but I am not convinced"

## Eight-section output

1. **Solution-jumping diagnosis**
   - What signal made this solution feel necessary?
   - What pain, failure, complaint or opportunity sits underneath?

2. **Underlying problem**
   - State the user, business or system problem without naming the proposed solution as the answer.

3. **Assumption challenges**
   - List the key assumptions behind the proposal.
   - For each: the risk if it is wrong, and a test that would validate it.

4. **Problem statement**
   - Who is affected, and in what context?
   - What breaks or slows down?
   - Why does it happen?
   - What does it cost?
   - What would be observably better?

5. **Three alternative framings**
   - Frame A: one reading of the problem and the solution space it opens.
   - Frame B: a different reading and a different solution space.
   - Frame C: a third.

6. **Evidence status**
   - None: mostly intuition.
   - Weak: one anecdote, one complaint, internal opinion.
   - Medium: repeated user signal, support tickets, product data, interviews.
   - Strong: qualitative and quantitative evidence that agree.

7. **Validation plan**
   - Data to inspect.
   - People to talk to.
   - Prototype or experiment to run.
   - What result would kill the idea.

8. **Draft stakeholder message**
   - A short note that keeps momentum without accepting the proposal blindly. Collaborative tone, not obstructive.

## Idea triage (reverse use)

For a session with many ideas:

1. Take one raw idea.
2. Extract the problem it claims to solve.
3. Assign an evidence status.
4. Drop ideas with no evidence unless they are cheap experiments.
5. Promote only ideas backed by real problem evidence.

## Rules

- Do not start by debating feasibility alone.
- First ask what pain would lead a reasonable team to propose this.
- Use the existing proposal as research evidence; this preserves the team's momentum.
- Challenge each assumption with a risk-if-wrong and a validation test.
- Produce at least three problem framings before recommending something to build.
- Include the stakeholder message so the user can push back constructively.
- Do not use this framework to delay an obvious fix when the evidence is already strong.

In the brainstorm report, place these eight sections (or a concise equivalent) before the implementation approaches.
