package io.archsim.model.component.cache;

import io.archsim.model.codec.JsonEnum;

/** What a full cache does: evict the least recently used keys, or refuse writes; auto follows the technology. */
public enum EvictionPolicy implements JsonEnum {
    AUTO("auto"),
    ALLKEYS_LRU("allkeys_lru"),
    NOEVICTION("noeviction");

    private final String json;

    EvictionPolicy(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
