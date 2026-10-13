package com.mc9y.pokemonapi.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class CustomEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private boolean cancelled;

    public CustomEvent(String name, Object object, Player player) {
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
