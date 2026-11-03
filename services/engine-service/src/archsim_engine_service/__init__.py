"""The Python engine's synchronous calls over HTTP, for the Java API during the port.

The Java API (archsim-app) serves every endpoint, but some answers still need the Python engine:
an analysis's sizes, cost and review, and later a run's prepared input, comparisons, exports,
imports and calibrations. This service runs those calls, one endpoint each, on the engine frozen
at 0.16.0. It keeps no state and no database, and listens only inside compose. Each endpoint goes
away when stage 3 of the port moves its call to Java (ADR-0036).
"""
