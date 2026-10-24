"""Built-in component models."""

from __future__ import annotations

from archsim_engine.components.cache import CacheConfig, CacheModel
from archsim_engine.components.database import DatabaseConfig, DatabaseModel
from archsim_engine.components.external_api import ExternalApiConfig, ExternalApiModel
from archsim_engine.components.load_balancer import LoadBalancerConfig, LoadBalancerModel
from archsim_engine.components.message_queue import MessageQueueConfig, MessageQueueModel
from archsim_engine.components.service import ServiceConfig, ServiceModel
from archsim_engine.components.traffic_source import TrafficSourceConfig, TrafficSourceModel
from archsim_engine.registry import ComponentModel, ComponentRegistry, load_entry_point_plugins

BUILTIN_MODELS: tuple[type[ComponentModel], ...] = (
    TrafficSourceModel,
    LoadBalancerModel,
    ServiceModel,
    CacheModel,
    DatabaseModel,
    ExternalApiModel,
    MessageQueueModel,
)


def create_default_registry(include_plugins: bool = True) -> ComponentRegistry:
    registry = ComponentRegistry(m() for m in BUILTIN_MODELS)
    if include_plugins:
        load_entry_point_plugins(registry)
    return registry


__all__ = [
    "BUILTIN_MODELS",
    "CacheConfig",
    "CacheModel",
    "DatabaseConfig",
    "DatabaseModel",
    "ExternalApiConfig",
    "ExternalApiModel",
    "LoadBalancerConfig",
    "LoadBalancerModel",
    "MessageQueueConfig",
    "MessageQueueModel",
    "ServiceConfig",
    "ServiceModel",
    "TrafficSourceConfig",
    "TrafficSourceModel",
    "create_default_registry",
]
