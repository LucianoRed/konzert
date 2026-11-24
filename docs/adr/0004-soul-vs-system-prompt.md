# ADR-0004: Soul vs. system prompt

## Status

Accepted (2026-07). Implemented: both are fields of the agent configuration.

## Context

An agent needs instructions and it needs a recognisable character. Mixing the
two in one text means every functional change risks the character, and nobody
can say which sentences are safe to edit.

## Decision

- **System prompt**: the agent's *job*. Role, capabilities, constraints, output
  contract, safety boundaries. Functional; changes when features change.
- **Soul**: the agent's *character*. Personality, values, tone, boundaries of
  behaviour. Together with name, avatar and colour it makes the agent
  recognisable. Rarely changes.

They are two separate fields (`systemPrompt` and `identity.soul`), seeded from
separate sources and editable separately at runtime.

The runtime prompt is assembled as **system prompt, then soul, then volatile
context**. The stable part comes first so the provider's prompt cache keeps
hitting; the event and the conversation tail come last.

## Consequences

- Feature work touches the system prompt; the character stays stable.
- One agent image can serve several agents: in the incident-response example
  Atlas and Sentinel differ by soul and configuration, not by code.
