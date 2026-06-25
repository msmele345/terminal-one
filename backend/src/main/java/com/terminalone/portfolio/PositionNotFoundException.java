package com.terminalone.portfolio;

/** Raised when a CRUD operation targets a position id that does not exist. */
public class PositionNotFoundException extends RuntimeException {

    public PositionNotFoundException(String kind, long id) {
        super(kind + " position " + id + " not found");
    }
}
