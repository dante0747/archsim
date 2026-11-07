"""The simulation a calibration replays, over HTTP, for the Java API during the port.

The Java API (archsim-app) serves every endpoint and calibrates in Java, but a calibration's
replay still needs the Python engine's simulator until stage 4 of the port. This service runs it
(``POST /simulate``) on the engine frozen at 0.16.0. It keeps no state and no database, calls
nothing, and listens only inside compose (ADR-0036, ADR-0045).
"""
