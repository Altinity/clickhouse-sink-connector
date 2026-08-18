package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.BitSet;

/**
 * An implementation of ANTLRErrorListener that handles parsing errors.
 * <p>
 * This class logs any syntax error encountered during parsing and
 * throws a runtime exception to indicate an error during DDL parsing.
 * </p>
 */
public class ErrorListenerImpl implements ANTLRErrorListener {

    /**
     * Logger instance for error logging.
     */
    private static final Logger log =
            LogManager.getLogger(ErrorListenerImpl.class);

    /**
     * Called when a syntax error is encountered during parsing.
     *
     * @param recognizer The parser instance.
     * @param offendingSymbol The symbol that caused the error.
     * @param line The line number where the error occurred.
     * @param charPositionInLine The character position in the line.
     * @param msg The error message.
     * @param e The exception thrown by the parser.
     */
    @Override
    public void syntaxError(Recognizer<?, ?> recognizer,
                            Object offendingSymbol, int line,
                            int charPositionInLine, String msg,
                            RecognitionException e) {
        String errorDetail = String.format("Error parsing DDL at line %d:%d - %s", line, charPositionInLine, msg);
        log.error(errorDetail);
        throw new RuntimeException(errorDetail);
    }

    /**
     * Reports an ambiguity encountered during parsing.
     *
     * @param parser The parser instance.
     * @param dfa The DFA where the ambiguity occurred.
     * @param startIndex The start index of the ambiguous input.
     * @param stopIndex The stop index of the ambiguous input.
     * @param exact True if the ambiguity is exact.
     * @param ambigAlts The set of ambiguous alternatives.
     * @param configs The ATN configuration set.
     */
    @Override
    public void reportAmbiguity(Parser parser, DFA dfa, int startIndex,
                                int stopIndex, boolean exact,
                                BitSet ambigAlts, ATNConfigSet configs) {
        // No implementation provided.
    }

    /**
     * Reports an attempt to use full context during parsing.
     *
     * @param parser The parser instance.
     * @param dfa The DFA in use.
     * @param startIndex The start index of the input.
     * @param stopIndex The stop index of the input.
     * @param conflictingAlts The set of conflicting alternatives.
     * @param configs The ATN configuration set.
     */
    @Override
    public void reportAttemptingFullContext(Parser parser, DFA dfa,
                                            int startIndex, int stopIndex,
                                            BitSet conflictingAlts,
                                            ATNConfigSet configs) {
        // No implementation provided.
    }

    /**
     * Reports context sensitivity encountered during parsing.
     *
     * @param parser The parser instance.
     * @param dfa The DFA in use.
     * @param startIndex The start index of the input.
     * @param stopIndex The stop index of the input.
     * @param prediction The prediction made.
     * @param configs The ATN configuration set.
     */
    @Override
    public void reportContextSensitivity(Parser parser, DFA dfa,
                                         int startIndex, int stopIndex,
                                         int prediction,
                                         ATNConfigSet configs) {
        // No implementation provided.
    }
}
