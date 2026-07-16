package net.buildtheearth.terraminusminus.substitutes.exceptions;

import net.buildtheearth.terraminusminus.substitutes.Biome;
import net.buildtheearth.terraminusminus.substitutes.Identifier;

/**
 * Thrown when a problem is encountered working with retained content
 * substitutes such as {@link Identifier} and {@link Biome}.
 */
public abstract class SubstituteException extends RuntimeException {

    public SubstituteException(String message) {
        super(message);
    }
}
