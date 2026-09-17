package it.gruppobernardini.switchmail.model;

/** Cosa fare della mail dopo l'elaborazione. Ammesso solo su account OWNED. */
public enum PostAction {
    NONE, MARK_SEEN, MOVE, DELETE
}
