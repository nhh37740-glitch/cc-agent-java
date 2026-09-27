# Agent contracts

This Gradle module contains the records and `AgentTool` interface shared by the
HTTP, Agent, tool, and persistence implementations. It uses Java 17 and Jackson
annotations only. It has no Spring Boot, database, HTTP client, or filesystem
implementation dependency. The root Spring Boot application depends on this
module; the contracts module cannot import classes from the application because
Gradle gives it no dependency back to the root project.
