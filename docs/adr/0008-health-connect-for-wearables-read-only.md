# ADR-0008: Health Connect for wearables, read-only

- **Status:** Accepted

## Context
Wearable context improves advice but is sensitive.

## Decision
Read only through Android Health Connect, only metrics the user grants, never write, never estimate missing values. Skin temperature and sweat rate are reported unavailable because no stable record type exists.

## Consequences
+ One integration covers many devices; strong consent model. - Data may be sparse or delayed.
