package com.example.BACKEND.analytics.sql.resolver;

import com.example.BACKEND.analytics.query.enums.EntityType;

public class EntityTableResolver {

    public static String resolve(EntityType entityType) {

        // Pseudocode


        System.out.println("Entered" + entityType);
        if (entityType == null) {
            throw new IllegalStateException("EntityType cannot be null");
        }

        return switch (entityType) {
            case EVENT -> "public.canonical_events";
            case USER -> "public.canonical_users";
        };
    }
}
