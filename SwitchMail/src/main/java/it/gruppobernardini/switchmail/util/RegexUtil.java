package it.gruppobernardini.switchmail.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Match di regex fornite dall'operatore, con budget di passi.
 *
 * <p>Una regex scritta a mano nella UI e' un vettore di backtracking catastrofico anche senza
 * malizia: {@code (a+)+$} su un oggetto lungo appende il thread di poll per sempre. L'input viene
 * avvolto in una CharSequence che conta gli accessi e lancia oltre il budget: il risultato e' una
 * regola visibilmente sbagliata (nessun match + warning) invece di un poller fermo.
 */
public final class RegexUtil {

    public static final int DEFAULT_MAX_STEPS = 100_000;

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_MAX = 512;

    private RegexUtil() {
    }

    /** Segnala che il budget di passi e' finito: la regola non matcha e l'evento va loggato. */
    public static class RegexBudgetExceededException extends RuntimeException {
        public RegexBudgetExceededException(String pattern, int maxSteps) {
            super("regex troppo costosa (oltre " + maxSteps + " passi): " + pattern);
        }
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        String key = pattern + '\0' + caseSensitive;
        Pattern cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("regex non valida: " + e.getDescription(), e);
        }
        if (CACHE.size() >= CACHE_MAX) {
            CACHE.clear();
        }
        CACHE.put(key, compiled);
        return compiled;
    }

    /** La cache si svuota a ogni scrittura di regola: i pattern cambiano, non devono restare appesi. */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Semantica substring ({@link Matcher#find()}, non {@code matches()}): l'operatore scrive
     * "AVVISI PARTENZA TRENO" e si aspetta che colpisca. Per ancorare usa ^...$, e l'help lo dice.
     */
    public static boolean find(String pattern, String input, boolean caseSensitive, int maxSteps) {
        if (input == null) {
            return false;
        }
        Matcher m = compile(pattern, caseSensitive).matcher(new BudgetedCharSequence(input, pattern, maxSteps));
        return m.find();
    }

    /** Restituisce il gruppo richiesto, o null se il pattern non trova nulla. */
    public static String firstGroup(String pattern, String input, int group, boolean caseSensitive, int maxSteps) {
        if (input == null) {
            return null;
        }
        Matcher m = compile(pattern, caseSensitive).matcher(new BudgetedCharSequence(input, pattern, maxSteps));
        return m.find() ? m.group(group) : null;
    }

    /** CharSequence che conta gli accessi: e' un budget, non un timeout (nessun thread da uccidere). */
    private static final class BudgetedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final String pattern;
        private final int maxSteps;
        private int steps;

        private BudgetedCharSequence(CharSequence delegate, String pattern, int maxSteps) {
            this.delegate = delegate;
            this.pattern = pattern;
            this.maxSteps = maxSteps;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++steps > maxSteps) {
                throw new RegexBudgetExceededException(pattern, maxSteps);
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
